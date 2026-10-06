package com.innbucks.marketplaceservice.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.web.client.RestClient;

/**
 * Distributed-tracing wiring that Boot's auto-configuration does not do on its
 * own (fleet convention, CLAUDE.md "Tracing"). Boot already traces incoming
 * requests, puts {@code traceId} / {@code spanId} in the MDC and builds the
 * tracer; this adds the three things specific to how this service is built.
 * Each is a customizer of what exists — no client builder is replaced.
 *
 * <ol>
 *   <li><b>Internal calls carry the trace.</b> The {@code @LoadBalanced}
 *       {@link RestClient.Builder} is a plain {@code RestClient.builder()}, which
 *       Boot's {@code RestClientCustomizer}s never see, so calls to user-service
 *       were not observed and sent no {@code traceparent}. The post-processor
 *       below hands that builder the {@link ObservationRegistry} — only
 *       {@code @LoadBalanced} builders, which can only reach fleet services.</li>
 *   <li><b>Partners never do.</b> The sender handler injects through
 *       {@link FleetOnlyPropagator}, so even an observed client never writes trace
 *       headers to a non-fleet host.</li>
 *   <li><b>{@code @Async} hand-offs keep the trace</b> — see
 *       {@link #traceContextTaskDecorator()}.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    /**
     * Replaces Boot's sender handler (it backs off: {@code @ConditionalOnMissingBean})
     * with the same handler over a {@link FleetOnlyPropagator}. Keeps Boot's ORDER:
     * tracing handlers are grouped first-match-wins, so at a later position the
     * default handler would claim client observations and nothing would be
     * propagated at all.
     */
    @Bean
    @Order(MicrometerTracingAutoConfiguration.SENDER_TRACING_OBSERVATION_HANDLER_ORDER)
    PropagatingSenderTracingObservationHandler<?> propagatingSenderTracingObservationHandler(
            Tracer tracer, Propagator propagator) {
        return new PropagatingSenderTracingObservationHandler<>(tracer, new FleetOnlyPropagator(propagator));
    }

    /**
     * Gives every {@code @LoadBalanced} {@link RestClient.Builder} the observation
     * registry, the way Spring Cloud LoadBalancer gives it its interceptor. A
     * client that {@code clone()}s the builder (UserNotifyGateway, the two
     * user-service resolvers) keeps it.
     * Static, as every {@link BeanPostProcessor} {@code @Bean} must be.
     */
    @Bean
    static BeanPostProcessor loadBalancedRestClientObservationPostProcessor(
            ObjectProvider<ObservationRegistry> observationRegistry, ApplicationContext context) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof RestClient.Builder builder
                        && context.findAnnotationOnBean(beanName, LoadBalanced.class) != null) {
                    observationRegistry.ifAvailable(builder::observationRegistry);
                }
                return bean;
            }
        };
    }

    /**
     * The task decorator for every {@code @Async} executor: the worker runs inside
     * the submitting thread's observation, so its log lines carry the same
     * {@code traceId} and anything it calls joins the same trace.
     *
     * <p>Scoped to the OBSERVATION alone, not every registered context. The
     * default {@link ContextPropagatingTaskDecorator} snapshots every
     * {@code ThreadLocalAccessor} on the classpath — Spring Security registers
     * one for {@code SecurityContextHolder} — so it would hand the caller's
     * authentication to the notification threads, which run without one today.
     * Applied in {@link AsyncConfig#boundedPool}, so all three pools get it.
     * Tracing must not change what those threads are authorised as.
     */
    public static TaskDecorator traceContextTaskDecorator() {
        ContextRegistry observationOnly = new ContextRegistry()
                .registerThreadLocalAccessor(ObservationThreadLocalAccessor.getInstance());
        return new ContextPropagatingTaskDecorator(
                ContextSnapshotFactory.builder().contextRegistry(observationOnly).build());
    }
}
