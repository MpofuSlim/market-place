package com.innbucks.marketplaceservice.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
import com.innbucks.marketplaceservice.notify.WhatsAppNotificationClient;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Distributed tracing through the REAL application context (fleet convention,
 * CLAUDE.md "Tracing"): the ids reach the log MDC, an incoming
 * {@code traceparent} is continued, the internal user-service call carries it
 * (through the discovery map and the LoadBalancer, as in the cell), partners
 * never get it, and every {@code @Async} pool keeps the trace.
 *
 * <p>user-service and the WhatsApp gateway are one WireMock: the discovery map
 * points {@code user-service} at it, exactly as the cell points it at the k8s
 * Service, so the request that arrives is the one the cell would send.
 *
 * <p>{@code @AutoConfigureTracing} is load-bearing. Without it Boot's test
 * support sets {@code management.tracing.export.enabled=false}, and in Boot 4
 * that also swaps in a no-op propagator — nothing would be extracted or injected
 * and every propagation case here would fail for a reason production never has.
 * It is the same reason {@code application.yaml} keeps that switch {@code true}
 * and turns export off through the missing endpoint instead.
 */
@AutoConfigureTracing
class TracingPropagationIT extends PostgresTestContainer {

    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT_OF_TRACE = "00-%s-[0-9a-f]{16}-[0-9a-f]{2}";
    private static final String PROBE_LOGGER = "tracing-probe";

    private static final WireMockServer WIREMOCK = startedWireMock();

    @DynamicPropertySource
    static void pointUpstreamsAtWireMock(DynamicPropertyRegistry registry) {
        String local = "http://localhost:" + WIREMOCK.port();
        registry.add("spring.cloud.discovery.enabled", () -> "true");
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", () -> local);
        registry.add("whatsapp.base-url", () -> local);
        registry.add("whatsapp.api-key", () -> "test-whatsapp-key");
    }

    @Autowired private ApplicationContext context;
    @Autowired private ObservationRegistry observationRegistry;
    @Autowired private Tracer tracer;
    @Autowired private UserNotifyGateway userNotifyGateway;
    @Autowired private WhatsAppNotificationClient whatsAppNotificationClient;
    @Autowired private AsyncProbe asyncProbe;
    @Autowired @Qualifier(AsyncConfig.NOTIFICATION_EXECUTOR) private Executor notificationExecutor;
    @Autowired @Qualifier(AsyncConfig.BULK_NOTIFICATION_EXECUTOR) private Executor bulkExecutor;
    @Autowired @Qualifier(AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR) private Executor securityExecutor;

    private final ListAppender<ILoggingEvent> probeLog = new ListAppender<>();

    @BeforeEach
    void stubUpstreamsAndCaptureTheProbe() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        probeLog.start();
        ((Logger) LoggerFactory.getLogger(PROBE_LOGGER)).addAppender(probeLog);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger(PROBE_LOGGER)).detachAppender(probeLog);
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    // ---- incoming requests + the log MDC ----------------------------------

    @Test
    void anIncomingTraceparent_isContinued_andItsIdsAreInTheLogMdc() throws Exception {
        mockMvc.perform(get("/marketplace/orders/mine")
                .header("traceparent", "00-" + INCOMING_TRACE_ID + "-" + INCOMING_SPAN_ID + "-01"));

        ILoggingEvent line = probeLine();
        assertThat(line.getMDCPropertyMap()).containsEntry("traceId", INCOMING_TRACE_ID);
        assertThat(line.getMDCPropertyMap().get("spanId"))
                .matches("[0-9a-f]{16}")
                .isNotEqualTo(INCOMING_SPAN_ID);
    }

    @Test
    void aRequestWithNoTraceparent_startsATrace() throws Exception {
        mockMvc.perform(get("/marketplace/orders/mine"));

        assertThat(probeLine().getMDCPropertyMap().get("traceId")).matches("[0-9a-f]{32}");
    }

    // ---- outbound: fleet yes, partners no ---------------------------------

    @Test
    void theUserServiceCall_carriesTheTrace_throughTheDiscoveryMap() {
        String traceId = inObservation(() -> userNotifyGateway.notify(UUID.randomUUID(), "Subject", "Message"));

        WIREMOCK.verify(postRequestedFor(urlPathMatching("/users/internal/.*/notify"))
                .withHeader("traceparent", matching(TRACEPARENT_OF_TRACE.formatted(traceId))));
    }

    @Test
    void thePartnerWhatsAppGateway_neverGetsTheTrace() {
        inObservation(() -> whatsAppNotificationClient.sendCustomNotification("+263771234567", "Hello"));

        WIREMOCK.verify(postRequestedFor(urlEqualTo("/api/messages/custom-notification"))
                .withoutHeader("traceparent")
                .withoutHeader("tracestate")
                .withoutHeader("baggage"));
    }

    @Test
    void evenAnObservedClient_sendsNoTrace_toANonFleetHost() {
        RestClient observedPartnerClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("http://localhost:" + WIREMOCK.port())
                .build();

        inObservation(() -> observedPartnerClient.get().uri("/partner/ping").retrieve().toBodilessEntity());

        WIREMOCK.verify(1, anyRequestedFor(urlEqualTo("/partner/ping")));
        WIREMOCK.verify(anyRequestedFor(urlEqualTo("/partner/ping")).withoutHeader("traceparent"));
    }

    @Test
    void theSenderHandlerInContext_isTheFleetOnlyOne() {
        assertThat(context.getBeansOfType(PropagatingSenderTracingObservationHandler.class)).hasSize(1);
        assertThat(context.getBean(Tracer.class)).isInstanceOf(OtelTracer.class);
    }

    @Test
    void withNoEndpointConfigured_noExporterExists() {
        assertThat(context.getBeansOfType(OtlpTracingConnectionDetails.class)).isEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
    }

    // ---- @Async hand-offs --------------------------------------------------

    @Test
    void everyPool_runsItsTaskInTheSubmittersTrace() throws Exception {
        for (Executor executor : List.of(notificationExecutor, bulkExecutor, securityExecutor)) {
            AtomicReference<String> onWorker = new AtomicReference<>();
            CompletableFuture<Void> done = new CompletableFuture<>();
            String traceId = inObservation(() -> executor.execute(() -> {
                onWorker.set(MDC.get("traceId"));
                done.complete(null);
            }));
            done.get(10, TimeUnit.SECONDS);
            assertThat(onWorker.get()).as("traceId on %s", executor).isEqualTo(traceId);
        }
    }

    @Test
    void anAsyncMethod_keepsTheTrace_onTheNamedAndTheDefaultExecutor() throws Exception {
        AtomicReference<CompletableFuture<String>> named = new AtomicReference<>();
        AtomicReference<CompletableFuture<String>> bare = new AtomicReference<>();
        String traceId = inObservation(() -> {
            named.set(asyncProbe.traceIdOnSecurityExecutor());
            bare.set(asyncProbe.traceIdOnDefaultExecutor());
        });

        assertThat(named.get().get(10, TimeUnit.SECONDS)).isEqualTo(traceId);
        assertThat(bare.get().get(10, TimeUnit.SECONDS)).isEqualTo(traceId);
    }

    @Test
    void theHandOff_carriesOnlyTheTrace_notTheCallersAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("caller", null, List.of()));
        AtomicReference<Object> authOnWorker = new AtomicReference<>("unset");
        CompletableFuture<Void> done = new CompletableFuture<>();

        inObservation(() -> notificationExecutor.execute(() -> {
            authOnWorker.set(SecurityContextHolder.getContext().getAuthentication());
            done.complete(null);
        }));
        done.get(10, TimeUnit.SECONDS);

        assertThat(authOnWorker.get()).isNull();
    }

    // ---- helpers -------------------------------------------------------------

    /** Runs {@code body} inside an observation (as a request would) and returns its trace id. */
    private String inObservation(Runnable body) {
        AtomicReference<String> traceId = new AtomicReference<>();
        Observation.createNotStarted("tracing-test", observationRegistry).observe(() -> {
            traceId.set(tracer.currentSpan().context().traceId());
            body.run();
        });
        assertThat(traceId.get()).matches("[0-9a-f]{32}");
        return traceId.get();
    }

    private ILoggingEvent probeLine() {
        assertThat(probeLog.list).as("the probe filter logged inside the request").isNotEmpty();
        return probeLog.list.get(probeLog.list.size() - 1);
    }

    private static WireMockServer startedWireMock() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @TestConfiguration
    static class ProbeConfig {

        /**
         * Logs one line from INSIDE request handling (after the observation filter,
         * before security), the way any service code would — this service has no
         * access-log filter of its own to read.
         */
        @Bean
        FilterRegistrationBean<Filter> tracingProbeFilter() {
            org.slf4j.Logger log = LoggerFactory.getLogger(PROBE_LOGGER);
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                log.info("probe");
                chain.doFilter(request, response);
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    /** Two @Async methods: one naming a pool, one taking the default. */
    static class AsyncProbe {
        @Async(AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR)
        public CompletableFuture<String> traceIdOnSecurityExecutor() {
            return CompletableFuture.completedFuture(MDC.get("traceId"));
        }

        @Async
        public CompletableFuture<String> traceIdOnDefaultExecutor() {
            return CompletableFuture.completedFuture(MDC.get("traceId"));
        }
    }
}
