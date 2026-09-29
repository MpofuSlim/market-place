package com.innbucks.marketplaceservice.seller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seller-name lookup never holds a pooled database connection.
 *
 * <p>The name comes from user-service over HTTP, and the public catalogue asks
 * for it on every page that shows a seller nobody here has named. It used to
 * be asked INSIDE the read transaction, so each in-flight lookup pinned one of
 * the pool's connections for up to the whole client timeout: a user-service
 * stall turned the unauthenticated catalogue into pool exhaustion for orders,
 * payment confirms and fulfilment alike.
 *
 * <p>Measured, not inferred: user-service is a WireMock stub with a fixed
 * delay, the lookup is really in flight on the wire, and the test thread reads
 * Hikari's own active-connection count while it is. The count must be ZERO.
 * With {@code spring.jpa.open-in-view} left at Boot's default this fails even
 * with the transaction gone — the request-scoped EntityManager keeps the
 * connection it first used until the response is written.
 */
@Import(NameLookupHoldsNoConnectionIT.SlowRegistryConfig.class)
class NameLookupHoldsNoConnectionIT extends PostgresTestContainer {

    private static final String PATH = "/users/internal/organizations/names";
    private static final String TOKEN = "dev-only-test-internal-token-9d5c7f8e2a1b3c4d";
    private static final int REGISTRY_DELAY_MS = 1500;
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    /** Started before the context so the bean below can be pointed at it. */
    static final WireMockServer REGISTRY = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        REGISTRY.start();
    }

    /**
     * The REAL resolver (so its own transaction guard is part of what is
     * proved), pointed at the slow stub, behind a gate that tells the test the
     * lookup has started. Disarmed during fixture setup so building the
     * listing does not wait on the stub.
     */
    static final class GatedResolver implements MerchantNameResolver {
        private final MerchantNameResolver delegate;
        volatile boolean armed;
        volatile CountDownLatch entered = new CountDownLatch(1);

        GatedResolver(MerchantNameResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<UUID, String> namesFor(Collection<UUID> merchantIds) {
            if (!armed) {
                return Map.of();
            }
            entered.countDown();
            return delegate.namesFor(merchantIds);
        }
    }

    @TestConfiguration
    static class SlowRegistryConfig {
        @Bean
        @Primary
        GatedResolver gatedResolver() {
            // ttl 0: every request asks, so no case can pass off a cache hit.
            return new GatedResolver(new UserServiceOrganizationNameResolver(
                    RestClient.builder(), "http://localhost:" + REGISTRY.port(),
                    2000, 5000, 0, 30, TOKEN, new SimpleMeterRegistry(), Clock.systemUTC()));
        }
    }

    @Autowired
    private GatedResolver resolver;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SellerService sellerService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private UUID merchantId;
    private String merchantToken;

    @AfterAll
    static void stopRegistry() {
        REGISTRY.stop();
    }

    @BeforeEach
    void setUp() {
        REGISTRY.resetAll();
        resolver.armed = false;
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
    }

    @AfterEach
    void tearDown() {
        resolver.armed = false;
        caller.shutdownNow();
    }

    private void stubSlowRegistry() {
        REGISTRY.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(REGISTRY_DELAY_MS)
                .withBody("""
                        {"code":"200 OK","message":"Organization names","data":[
                          {"organizationId":"%s","name":"Rudo Traders"}]}""".formatted(merchantId))));
    }

    /** An ACTIVE listing whose seller has NO local display name — the case
     *  that makes the catalogue ask the registry. */
    private String activeListing() throws Exception {
        String body = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar lantern","categoryCode":"other",
                                 "priceCents":1500,"stockQty":5}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", id)
                        .file(new MockMultipartFile("image", "p.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", id)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private int activeConnections() throws Exception {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    private String getWhileLookupInFlight(String url) throws Exception {
        return whileLookupInFlight(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url));
    }

    /**
     * Runs the request on another thread, waits until the name lookup is on
     * the wire, samples the pool for most of the stub's delay, then returns the
     * response body. Every sample must read zero.
     */
    private String whileLookupInFlight(RequestBuilder request) throws Exception {
        stubSlowRegistry();
        resolver.entered = new CountDownLatch(1);
        resolver.armed = true;
        Future<String> response = caller.submit(() -> mockMvc
                .perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(resolver.entered.await(10, TimeUnit.SECONDS))
                .as("the request reached the name lookup").isTrue();
        List<Integer> samples = new ArrayList<>();
        // Sampled from 200ms to ~1000ms into a 1500ms upstream delay: well
        // inside the HTTP call, well before it returns.
        Thread.sleep(200);
        for (int i = 0; i < 5; i++) {
            samples.add(activeConnections());
            Thread.sleep(150);
        }
        boolean stillInFlight = !response.isDone();
        // Let the request finish BEFORE asserting, so a failure here cannot
        // leave a transaction open into the next test's TRUNCATE.
        String body = response.get(10, TimeUnit.SECONDS);

        assertThat(stillInFlight).as("the lookup was still in flight while sampling").isTrue();
        assertThat(samples).as("pooled connections held while user-service is being asked")
                .containsOnly(0);
        // And the lookup really went over the wire — not skipped by the guard.
        REGISTRY.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        return body;
    }

    @Test
    @DisplayName("Public browse: no connection is held while the seller name is fetched")
    void browseHoldsNoConnection() throws Exception {
        activeListing();

        String body = getWhileLookupInFlight("/marketplace/catalog");

        assertThat((String) JsonPath.read(body, "$.data.items[0].seller.displayName"))
                .isEqualTo("Rudo Traders");
    }

    @Test
    @DisplayName("Public listing detail: no connection is held while the seller name is fetched")
    void getByIdHoldsNoConnection() throws Exception {
        String id = activeListing();

        String body = getWhileLookupInFlight("/marketplace/catalog/" + id);

        assertThat((String) JsonPath.read(body, "$.data.seller.displayName"))
                .isEqualTo("Rudo Traders");
    }

    @Test
    @DisplayName("Public seller profile: no connection is held while the seller name is fetched")
    void merchantProfileHoldsNoConnection() throws Exception {
        activeListing();

        String body = getWhileLookupInFlight("/marketplace/catalog/merchants/" + merchantId);

        assertThat((String) JsonPath.read(body, "$.data.displayName")).isEqualTo("Rudo Traders");
    }

    @Test
    @DisplayName("Add to cart: the write commits first, then the cart is named holding no connection")
    void cartAddHoldsNoConnectionAndStillNamesTheSeller() throws Exception {
        String id = activeListing();
        String customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);

        // The cart write answers with the whole cart. Rendered inside the
        // write's transaction it would have got the resolver's cache-only
        // answer (a nameless seller on the shopper's main screen); rendered
        // after the commit it asks user-service and holds nothing while it does.
        String body = whileLookupInFlight(post("/marketplace/cart/items")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(id)));

        assertThat((String) JsonPath.read(body, "$.data.items[0].listing.seller.displayName"))
                .isEqualTo("Rudo Traders");
    }

    @Test
    @DisplayName("Inside a transaction the resolver never calls out — it answers from its cache")
    void insideATransactionTheRegistryIsNotAsked() {
        stubSlowRegistry();
        resolver.armed = true;

        Map<UUID, String> names = new TransactionTemplate(transactionManager).execute(tx ->
                sellerService.displayNames(List.of(merchantId), Map.of()));

        // A write path renders from the cache (cold here), and the transaction
        // it holds — row locks included — never waits on user-service.
        assertThat(names).doesNotContainKey(merchantId);
        REGISTRY.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
