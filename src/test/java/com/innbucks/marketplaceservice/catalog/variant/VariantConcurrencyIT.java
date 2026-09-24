package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V19's stock model under real concurrency, against real Postgres.
 *
 * <p>For a listing with options the guarded {@code listing_variant} UPDATE is
 * the ONLY oversell guard, and {@code listing.stock_qty} is a total recomputed
 * under the listing's row lock. The lock rule that keeps this deadlock-free
 * (a listing row before its option rows; across listings in
 * {@code java.util.UUID} order; reserves and returns in the SAME order;
 * {@code FOR NO KEY UPDATE}) is invisible to mocked-repository tests, so each
 * case here fires real requests at the same instant through the real security
 * chain and then checks the exact numbers.
 *
 * <p>Every case ends on {@link ListingRepository#findStockDrift()} being empty:
 * whatever interleaving happened, no listing's total may disagree with its
 * options. A deadlock would surface as a 500 (Postgres aborts one side with
 * 40P01, which nothing maps to a 4xx), so every case also pins the exact
 * status of every request.
 */
class VariantConcurrencyIT extends PostgresTestContainer {

    /** Real PNG signature + filler — the publish gate needs a primary image. */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    private static final int BUYERS = 8;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private ListingRepository listingRepository;

    private String merchantToken;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        pool = Executors.newFixedThreadPool(BUYERS + 2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("Eight buyers racing for the LAST unit of one option: exactly one order, "
            + "every other buyer a 409 insufficient_stock, and the total follows the option")
    void lastUnitOfOneOptionIsSoldExactlyOnce() throws Exception {
        String listingId = published("""
                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                 "options":["Size","Colour"],
                 "variants":[{"values":["M","Black"],"stockQty":1},
                             {"values":["L","Black"],"stockQty":5}]}""");
        String m = variantId(listingId, "M");

        List<Callable<MockHttpServletRequestBuilder>> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            buyers.add(() -> order(item(listingId, m, 1)));
        }
        List<MockHttpServletResponse> responses = together(buyers);

        List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        // Every loser is refused for the same honest reason, whichever of the
        // two refusal paths it met: the advisory pricer (the winner had already
        // committed) or the guarded option UPDATE (it waited on the listing
        // lock and then found nothing left). Both name the option.
        for (MockHttpServletResponse loser : responses) {
            if (loser.getStatus() == 409) {
                String body = loser.getContentAsString();
                assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("insufficient_stock");
                assertThat((String) JsonPath.read(body, "$.message")).isEqualTo(
                        "Insufficient stock for listing " + listingId + " variant " + m);
            }
        }

        assertThat(variantStock(m)).isZero();
        assertThat(variantStock(variantId(listingId, "L"))).isEqualTo(5);
        assertThat(listingStock(listingId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM market_order_item WHERE variant_id = ?::uuid",
                Integer.class, m)).isEqualTo(1);
        assertThat(listingRepository.findStockDrift()).isEmpty();
    }

    @Test
    @DisplayName("Two orders naming the same two listings in OPPOSITE line order, plus a "
            + "cancel returning stock to both, all complete - the reserve and the return "
            + "take the listing locks in one global order, so there is no deadlock")
    void oppositeLineOrdersAndAConcurrentCancelNeverDeadlock() throws Exception {
        String options = published("""
                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                 "options":["Size"],
                 "variants":[{"values":["M"],"stockQty":100},
                             {"values":["L"],"stockQty":100}]}""");
        String plain = published("""
                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                 "priceCents":1550,"stockQty":100}""");
        String m = variantId(options, "M");
        String l = variantId(options, "L");

        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            // An earlier order over BOTH listings; its cancel returns to both,
            // taking the same two listing locks the new orders are taking.
            String earlierBuyer = customerToken();
            String earlierOrderId = placeOrder(earlierBuyer,
                    item(plain, null, 1), item(options, l, 1));

            List<MockHttpServletResponse> responses = together(List.of(
                    () -> order(item(options, m, 1), item(plain, null, 1)),
                    () -> order(item(plain, null, 1), item(options, l, 1)),
                    () -> post("/marketplace/orders/{id}/cancel", earlierOrderId)
                            .header("Authorization", "Bearer " + earlierBuyer)));

            assertThat(responses).extracting(MockHttpServletResponse::getStatus)
                    .as("round %d: two orders created and the cancel applied - never a 500", round)
                    .containsExactly(201, 201, 200);
        }

        // Each round: the first order took M + plain, the second plain + L, and
        // the earlier order's plain + L came back.
        assertThat(variantStock(m)).isEqualTo(100 - rounds);
        assertThat(variantStock(l)).isEqualTo(100 - rounds);
        assertThat(listingStock(options)).isEqualTo(200 - 2 * rounds);
        assertThat(listingStock(plain)).isEqualTo(100 - 2 * rounds);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM market_order WHERE status = 'CANCELLED' AND stock_released",
                Integer.class)).isEqualTo(rounds);
        assertThat(listingRepository.findStockDrift()).isEmpty();
    }

    @Test
    @DisplayName("A seller's quick restock of one option, racing checkouts on ANOTHER option "
            + "of the same listing: the restocked option holds exactly the seller's number "
            + "and every checkout's reservation survives")
    void quickRestockOfOneOptionRacingCheckoutsOnAnotherLeavesExactNumbers() throws Exception {
        String listingId = published("""
                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                 "options":["Size"],
                 "variants":[{"values":["M"],"stockQty":3},
                             {"values":["L"],"stockQty":20}]}""");
        String m = variantId(listingId, "M");
        String l = variantId(listingId, "L");

        List<Callable<MockHttpServletRequestBuilder>> requests = new ArrayList<>();
        requests.add(() -> patch("/marketplace/listings/{id}/variants/{variantId}/stock", listingId, m)
                .header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stockQty\":50}"));
        for (int i = 0; i < BUYERS; i++) {
            requests.add(() -> order(item(listingId, l, 1)));
        }
        List<MockHttpServletResponse> responses = together(requests);

        assertThat(responses.getFirst().getStatus()).isEqualTo(200);
        assertThat(responses.subList(1, responses.size()))
                .extracting(MockHttpServletResponse::getStatus)
                .containsOnly(201);
        // The PATCH answers with the option it set, whichever side of the
        // checkouts it ran on.
        String patched = responses.getFirst().getContentAsString();
        List<Integer> patchedStock = JsonPath.read(patched,
                "$.data.variants[?(@.id == '" + m + "')].stockQty");
        assertThat(patchedStock).containsExactly(50);

        assertThat(variantStock(m)).isEqualTo(50);
        assertThat(variantStock(l)).isEqualTo(20 - BUYERS);
        assertThat(listingStock(listingId)).isEqualTo(50 + 20 - BUYERS);
        assertThat(listingRepository.findStockDrift()).isEmpty();
    }

    @Test
    @DisplayName("A full editor save that KEEPS an option's stock (no stockQty sent), racing "
            + "checkouts on that very option, never writes back a stale count - no "
            + "reservation is lost and none is oversold")
    void editorSaveThatKeepsAnOptionRacingItsCheckoutsLosesNoReservation() throws Exception {
        String listingId = published("""
                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                 "options":["Size"],
                 "variants":[{"values":["M"],"stockQty":3},
                             {"values":["L"],"stockQty":20}]}""");
        String m = variantId(listingId, "M");
        String l = variantId(listingId, "L");

        List<Callable<MockHttpServletRequestBuilder>> requests = new ArrayList<>();
        requests.add(() -> put("/marketplace/listings/{id}", listingId)
                .header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Cotton Crew Tee (restocked)","categoryCode":"other",
                         "priceCents":1999,"options":["Size"],
                         "variants":[{"id":"%s","values":["M"],"stockQty":30},
                                     {"id":"%s","values":["L"]}]}""".formatted(m, l)));
        for (int i = 0; i < BUYERS; i++) {
            requests.add(() -> order(item(listingId, l, 1)));
        }
        List<MockHttpServletResponse> responses = together(requests);

        assertThat(responses.getFirst().getStatus()).isEqualTo(200);
        assertThat(responses.subList(1, responses.size()))
                .extracting(MockHttpServletResponse::getStatus)
                .containsOnly(201);

        assertThat(variantStock(m)).isEqualTo(30);
        assertThat(variantStock(l)).isEqualTo(20 - BUYERS);
        assertThat(listingStock(listingId)).isEqualTo(30 + 20 - BUYERS);
        assertThat(jdbc.queryForObject("SELECT title FROM listing WHERE id = ?::uuid",
                String.class, listingId)).isEqualTo("Cotton Crew Tee (restocked)");
        assertThat(listingRepository.findStockDrift()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String customerToken() {
        return TestJwts.customer(UUID.randomUUID(), jwtSecret);
    }

    /** One order line as JSON; a null variant is a line on a plain listing. */
    private static String item(String listingId, String variantId, int quantity) {
        return variantId == null
                ? "{\"listingId\":\"%s\",\"quantity\":%d}".formatted(listingId, quantity)
                : "{\"listingId\":\"%s\",\"quantity\":%d,\"variantId\":\"%s\"}"
                        .formatted(listingId, quantity, variantId);
    }

    /** A fresh buyer ordering these lines (in this order) under a fresh key. */
    private MockHttpServletRequestBuilder order(String... items) {
        return post("/marketplace/orders")
                .header("Authorization", "Bearer " + customerToken())
                .header("Idempotency-Key", "variant-race-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"buyerMsisdn\":\"+263771234567\",\"items\":[%s]}"
                        .formatted(String.join(",", items)));
    }

    /** Places an order sequentially, as {@code token}'s buyer, and returns its id. */
    private String placeOrder(String token, String... items) throws Exception {
        String json = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "variant-earlier-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyerMsisdn\":\"+263771234567\",\"items\":[%s]}"
                                .formatted(String.join(",", items))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.id");
    }

    /**
     * Builds every request first, then releases them at the same instant and
     * returns the responses in submission order.
     */
    private List<MockHttpServletResponse> together(List<Callable<MockHttpServletRequestBuilder>> requests)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();
        for (Callable<MockHttpServletRequestBuilder> request : requests) {
            results.add(pool.submit(() -> {
                MockHttpServletRequestBuilder builder = request.call();
                ready.countDown();
                start.await();
                return mockMvc.perform(builder).andReturn().getResponse();
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> result : results) {
            responses.add(result.get(60, TimeUnit.SECONDS));
        }
        return responses;
    }

    /** Creates a listing from {@code body}, gives it a primary image and puts it on sale. */
    private String published(String body) throws Exception {
        return publish(create(body));
    }

    private String create(String body) throws Exception {
        String json = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.id");
    }

    private String publish(String listingId) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return listingId;
    }

    private String variantId(String listingId, String firstValue) {
        return jdbc.queryForObject("""
                SELECT id::text FROM listing_variant
                 WHERE listing_id = ?::uuid AND option1_value = ?""",
                String.class, listingId, firstValue);
    }

    private int variantStock(String variantId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing_variant WHERE id = ?::uuid",
                Integer.class, variantId);
    }

    private int listingStock(String listingId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }
}
