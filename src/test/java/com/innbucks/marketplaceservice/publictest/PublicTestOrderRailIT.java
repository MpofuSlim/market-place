package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public surface with an api-key configured — the posture in which the
 * ORDER endpoints are served.
 *
 * <p>Separate from {@link PublicTestSurfaceIT} because the two prove opposite
 * halves of the same rule and cannot share a configuration: that class runs
 * UNGATED and proves ordering is absent there, this one runs GATED and proves
 * the journey works. A single class covering both would have to flip a property
 * mid-run, which is exactly how a gate stops being tested.
 *
 * <p>What is under test is not only that a buyer can buy. It is that widening
 * this surface to orders widened it <em>as a buyer</em> and nothing else: the
 * payer is validated rather than assumed, one handle cannot touch another's
 * order, and no seller or operator action became reachable.
 */
@TestPropertySource(properties = {
        "marketplace.public-test.enabled=true",
        "marketplace.public-test.api-key=" + PublicTestOrderRailIT.API_KEY
})
class PublicTestOrderRailIT extends PostgresTestContainer {

    static final String API_KEY = "order-rail-integration-test-key";

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String merchantToken;

    @BeforeEach
    void mintMerchantToken() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
    }

    @Test
    @DisplayName("cart -> order -> read back, with no token at any point")
    void aTokenlessCallerHoldingTheKeyCanBuy() throws Exception {
        String listingId = publishListing();
        addToCart("alice", listingId, 2);

        String created = mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "public-rail-attempt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567","deliveryMethod":"COLLECTION"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                // The payment block is what sends the app to payment-service
                // next; without it a token-less buyer could order and then have
                // no way to pay, which is the gap this rail exists to close.
                .andExpect(jsonPath("$.data.payment").exists())
                .andExpect(jsonPath("$.data.orderRef").exists())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");

        // The payer is the body's number, normalised to E.164 — asserted AT
        // REST rather than off the response, because OrderResponse deliberately
        // does not echo it back and this is the value payment-service will
        // later prompt. The derived caller carries no phone claim, so unlike
        // the authenticated twin the body is the only source there is.
        assertThat(jdbc.queryForObject(
                "SELECT buyer_msisdn FROM market_order WHERE id = ?::uuid", String.class, orderId))
                .isEqualTo("+263771234567");

        // Unlike the cart and the quote, this one DID hold stock — which is
        // the whole reason it sits behind the extra gate.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(8));

        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/orders/{o}", "alice", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId));

        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/orders", "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void theSameIdempotencyKeyReplaysRatherThanBuyingTwice() throws Exception {
        String listingId = publishListing();
        addToCart("alice", listingId, 1);

        String body = """
                {"fromCart":true,"buyerMsisdn":"0771234567","deliveryMethod":"COLLECTION"}""";
        String first = mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "retry-me")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // Extracted BEFORE the matcher: JsonPath.read is generic and inlining it
        // into .value(...) makes the compiler pick the value(Matcher) overload,
        // which ClassCastExceptions at runtime.
        String firstId = JsonPath.read(first, "$.data.id");

        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "retry-me")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data.id").value(firstId));

        // One order's worth of stock held, not two.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(9));
    }

    @Test
    void anOrderWithNoPayerNumberIsRefusedAndReservesNothing() throws Exception {
        String listingId = publishListing();
        addToCart("alice", listingId, 1);

        // An order with no payer cannot be paid, and inventing one is the
        // phishing shape this rail must never have. So it is a clean refusal.
        // The Idempotency-Key is sent because the service checks IT first
        // (400 idempotency_key_required) — this test is about the payer check
        // one step further in.
        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "no-payer-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_msisdn"));

        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));
    }

    @Test
    void anUndialableNumberIsRefusedRatherThanStored() throws Exception {
        String listingId = publishListing();
        addToCart("alice", listingId, 1);

        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "bad-number-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"not-a-number"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_msisdn"));
    }

    @Test
    void oneHandleCannotReadOrActOnAnothersOrder() throws Exception {
        String listingId = publishListing();
        addToCart("alice", listingId, 1);
        String created = mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "isolation-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");

        // Ownership keys on the DERIVED buyer id, so holding the api-key buys
        // a caller nothing but their own handle's orders. Owner-masked 404s,
        // identical to the authenticated surface — never a 403, which would
        // confirm the order exists.
        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/orders/{o}", "bob", orderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders/{o}/cancel", "bob", orderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/orders", "bob")))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("a phone handle is the customer: one basket across spellings, and the payer is the identity")
    void aPhoneHandleIsTheCustomer() throws Exception {
        String listingId = publishListing();

        // Added under the local spelling…
        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/cart/items", "0772000111"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":2}".formatted(listingId)))
                .andExpect(status().isOk());

        // …read back under E.164: the SAME basket. This is the loyalty-parity
        // rule — the customer exists at Veengu, the phone is the identity, and
        // no spelling of it forks a second cart.
        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/cart", "+263772000111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(2));

        // The order needs NO buyerMsisdn — the payer is the basket's owner —
        // and a body naming a DIFFERENT number is ignored, exactly as it is
        // for a real customer token. This is the line that stops the broker
        // (or anyone holding the key) aiming a PIN prompt at a third number
        // while acting as this basket.
        String created = mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "0772000111"))
                        .header("Idempotency-Key", "phone-identity-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"+263779999999"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");

        assertThat(jdbc.queryForObject(
                "SELECT buyer_msisdn FROM market_order WHERE id = ?::uuid", String.class, orderId))
                .isEqualTo("+263772000111");
    }

    @Test
    void theApiKeyIsStillRequiredOnEveryOrderCall() throws Exception {
        // The gate is the filter's, by shape over the whole prefix. Pinned here
        // because the order endpoints are the ones where losing it matters.
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567"}"""))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders", "alice")
                        .header("x-api-key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancellingReturnsTheHeldStock() throws Exception {
        String listingId = publishListing();
        addToCart("alice", listingId, 3);
        String created = mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders", "alice"))
                        .header("Idempotency-Key", "cancel-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");

        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(7));

        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/orders/{o}/cancel", "alice", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));
    }

    @Test
    void aReviewStillNeedsAPaidOrderEvenHere() throws Exception {
        String listingId = publishListing();

        // The verified-purchase gate is the service's, and it is not softened
        // by the caller arriving on this rail: an unpaid handle is refused.
        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/listings/{id}/reviews",
                        "alice", listingId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"Great lantern\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review_requires_purchase"));
    }

    @Test
    void noSellerOrOperatorSurfaceIsReachableFromThisRail() throws Exception {
        // Widening this surface to orders widened it as a BUYER. The derived
        // caller holds CUSTOMER and nothing else, so every seller and operator
        // endpoint is refused on its own @PreAuthorize — holding the api-key
        // changes none of that.
        mockMvc.perform(get("/marketplace/fulfilments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/settlements/summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/reports"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/listings/mine"))
                .andExpect(status().isUnauthorized());

        // And there is no public twin of any of them. WITH the key, so the
        // refusal is the router's 404 (no such mapping) and not the filter's
        // 401 — the filter gates the whole prefix by shape, nonexistent paths
        // included, which the unkeyed probes below pin on their own.
        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/fulfilments", "alice")))
                .andExpect(status().isNotFound());
        mockMvc.perform(keyed(get("/marketplace/public/buyers/{handle}/settlements", "alice")))
                .andExpect(status().isNotFound());

        // Unkeyed, the same paths are 401 before routing even looks: on a gated
        // cell the api-key filter answers for everything under the prefix, so a
        // prober without the key cannot even map which endpoints exist.
        mockMvc.perform(get("/marketplace/public/buyers/{handle}/fulfilments", "alice"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/public/buyers/{handle}/settlements", "alice"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- helpers

    /** Every call on this prefix carries the key — the filter gates by shape. */
    private static MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder builder) {
        return builder.header("x-api-key", API_KEY);
    }

    private void addToCart(String handle, String listingId, int quantity) throws Exception {
        mockMvc.perform(keyed(post("/marketplace/public/buyers/{handle}/cart/items", handle))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":%d}".formatted(listingId, quantity)))
                .andExpect(status().isOk());
    }

    private String publishListing() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");

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
}
