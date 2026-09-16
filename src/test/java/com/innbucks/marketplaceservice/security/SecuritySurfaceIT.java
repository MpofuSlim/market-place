package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the deployed security surface end to end — the real
 * {@code SecurityFilterChain}, {@code JwtFilter}, method security, the
 * internal-token trust boundary, and the metrics scrape gate — with SPECIFIC
 * status codes (fleet rule: never {@code .is4xxClientError()}, which would
 * pass for the wrong rejection layer).
 */
class SecuritySurfaceIT extends PostgresTestContainer {

    /** Valid per Bean Validation on purpose: argument resolution (and thus
     *  {@code @Valid}) runs BEFORE method security, so an invalid body would
     *  400 and mask the authorization outcome under test. */
    private static final String VALID_LISTING_BODY = """
            {
              "title": "Wireless Bluetooth Speaker",
              "description": "Portable speaker with 12h battery life.",
              "categoryCode": "electronics",
              "priceCents": 2599,
              "stockQty": 120
            }""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    void anonymousCatalogBrowseIsPublic() throws Exception {
        mockMvc.perform(get("/marketplace/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    void anonymousListingWriteIsUnauthorized() throws Exception {
        mockMvc.perform(post("/marketplace/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LISTING_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void customerRoleCannotManageListings() throws Exception {
        // Authenticated but neither MERCHANT_ADMIN nor SUPER_ADMIN (the only
        // listing-admin roles, by owner decision): method security
        // refuses INSIDE handler invocation — must render the fleet 403
        // envelope, not fall into the Exception catch-all as a 500.
        String customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LISTING_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void superAdminPassesTheListingGateAndFailsOnlyOnTheMissingTargetMerchant() throws Exception {
        // SUPER_ADMIN is admitted by the class-level hasAnyRole gate; without
        // a target merchantId in the body the SERVICE refuses with 400
        // merchant_id_required — proving the refusal is the on-behalf rule,
        // not a role 403 (admin tokens carry no merchantId claim).
        String adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LISTING_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("merchant_id_required"));
    }

    @Test
    void anonymousImageGetIsPublicUnknownListingIs404NotUnauthorized() throws Exception {
        // Pins that SecurityConfig's GET /marketplace/catalog/** permitAll
        // covers the new /{id}/image path: an anonymous probe reaches the
        // controller (404 image_not_found), it is never bounced with a 401.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
    }

    @Test
    void anonymousCategoryTreeIsPublic() throws Exception {
        // /marketplace/categories sits OUTSIDE the /marketplace/catalog/**
        // permitAll prefix — this pins SecurityConfig's dedicated GET matcher
        // for the exact path (without it the tree would 401 anonymously).
        mockMvc.perform(get("/marketplace/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[?(@.code == 'electronics')]").exists());
    }

    @Test
    void anonymousReportPostIsUnauthorized() throws Exception {
        // /marketplace/catalog/** is permitAll for GET ONLY — the report POST
        // under the same prefix must ride anyRequest().authenticated().
        // Reporting is never anonymous (spam control); a permitAll matcher
        // accidentally widened to all methods would make this test fail.
        mockMvc.perform(post("/marketplace/catalog/{id}/report", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SCAM\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anonymousReviewReadIsPublicUnknownListingIs404NotUnauthorized() throws Exception {
        // The public review read sits under the GET-scoped catalog permitAll:
        // an anonymous probe reaches the controller (404 listing_not_found for
        // an unknown id) — it is never bounced with a 401.
        mockMvc.perform(get("/marketplace/catalog/{id}/reviews", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("listing_not_found"));
    }

    @Test
    void anonymousMerchantRatingIsPublic() throws Exception {
        // Unknown/review-less merchant is a 200 {ratingAvg: null, reviewCount: 0},
        // reachable without a token (public trust signal on the catalog).
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}/rating", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    @Test
    void customerCannotReadModerationQueue() throws Exception {
        // /marketplace/reports is the SUPER_ADMIN moderation surface — any
        // other role gets the fleet 403 from method security.
        String customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/reports")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void merchantCannotUseCustomerFavorites() throws Exception {
        // Favorites are a buyer surface: CUSTOMER-only by class-level gate.
        String merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/favorites")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void customerCannotListAllOrders() throws Exception {
        // GET /marketplace/orders is the SUPER_ADMIN oversight read — a
        // CUSTOMER keeps /mine and must be refused here with the fleet 403.
        String customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void merchantAdminCannotReadOrders() throws Exception {
        // Order reads are CUSTOMER (own) or SUPER_ADMIN (any) — merchant
        // tokens get the same 403 on both the by-id and the oversight read.
        String merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/orders/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/marketplace/orders")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void superAdminCannotPlaceOrCancelOrders() throws Exception {
        // Oversight is read-only on the order surface: placing/cancelling
        // stays CUSTOMER-only, so an admin token is refused with the fleet 403.
        String adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "admin-cannot-order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d","quantity":1}]}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void internalEndpointWithoutTokenIsIndistinguishableFromNotFound() throws Exception {
        // The fleet webhook convention: a failed trust-boundary check answers
        // the SAME 404 as an unknown ref, so a probe can't confirm the S2S
        // surface exists.
        mockMvc.perform(get("/marketplace/internal/orders/{ref}", "MKT-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));
    }

    @Test
    void internalEndpointWithWrongTokenIsIndistinguishableFromNotFound() throws Exception {
        mockMvc.perform(get("/marketplace/internal/orders/{ref}", "MKT-000000000000")
                        .header("X-Internal-Token", "definitely-not-the-internal-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));
    }

    @Test
    void prometheusWithoutScrapeTokenIsRejected() throws Exception {
        // monitoring.scrape-token is blank in application-test.yaml, so the
        // gate is fail-closed: nothing scrapes until a cell provisions the
        // token — never a 200.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusWithWrongScrapeTokenIsRejected() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Metrics-Token", "definitely-not-the-scrape-token"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Checkout journey surfaces (V9) — all under /marketplace/**, so the
    // fleet gateway needs no new route; each one is authenticated and
    // role-gated here.
    // ------------------------------------------------------------------

    @Test
    void anonymousCartIsUnauthorized() throws Exception {
        // The cart is a specific shopper's, resolved from the token — there is
        // no anonymous cart to serve.
        mockMvc.perform(get("/marketplace/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void merchantCannotUseTheBuyerCart() throws Exception {
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + TestJwts.merchantAdmin(
                                UUID.randomUUID(), UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousAddressBookIsUnauthorized() throws Exception {
        mockMvc.perform(get("/marketplace/addresses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void merchantCannotReadTheBuyerAddressBook() throws Exception {
        // Delivery addresses are buyer PII; a seller reaches a destination only
        // through their own fulfilment parcel.
        mockMvc.perform(get("/marketplace/addresses")
                        .header("Authorization", "Bearer " + TestJwts.merchantAdmin(
                                UUID.randomUUID(), UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCheckoutOptionsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/marketplace/checkout/options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerReadsThisCellsCheckoutOptions() throws Exception {
        mockMvc.perform(get("/marketplace/checkout/options")
                        .header("Authorization", "Bearer " + TestJwts.customer(
                                UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isOk())
                // The fail-safe default: only the rail every cell has.
                .andExpect(jsonPath("$.data.paymentMethods[0].rail").value("INNBUCKS_CODE"))
                .andExpect(jsonPath("$.data.paymentEndpoint").value("POST /payments"))
                .andExpect(jsonPath("$.data.paymentOrderType").value("MARKETPLACE"));
    }

    @Test
    void customerCannotReadASellersFulfilmentQueue() throws Exception {
        // The queue carries destinations and buyer phone numbers for every
        // parcel that seller owes — never a buyer surface.
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + TestJwts.customer(
                                UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousFulfilmentQueueIsUnauthorized() throws Exception {
        mockMvc.perform(get("/marketplace/fulfilments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMerchantTokenWithNoMerchantScopeCannotReadTheQueue() throws Exception {
        // Merchant scope comes from the JWT, never from a request body — a
        // token without one is refused rather than defaulted to "all".
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + TestJwts.merchantAdminWithoutMerchant(
                                UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("merchant_scope_missing"));
    }

    @Test
    void anonymousSettlementsReadIsUnauthorized() throws Exception {
        mockMvc.perform(get("/marketplace/settlements"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotReadSettlements() throws Exception {
        // A buyer's half of the escrow is the dispute on their ORDER; the
        // settlement ledger itself is seller/operator money data.
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + TestJwts.customer(
                                UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void aMerchantCannotTouchTheOperatorSettlementSurfaces() throws Exception {
        // The dispute queue, the payout run and the payout report are all
        // SUPER_ADMIN: a seller must not resolve disputes over their own
        // money or read every other seller's payables.
        String merchantToken = TestJwts.merchantAdmin(
                UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/settlements/disputes")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"PAYOUT-1\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/marketplace/settlements/payout-report")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMerchantCannotDisputeAnOrder() throws Exception {
        // Disputing rides the buyer's order surface (CUSTOMER-only) — a
        // seller freezing (or steering) their own settlement is nonsense.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        UUID.randomUUID(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + TestJwts.merchantAdmin(
                                UUID.randomUUID(), UUID.randomUUID(), jwtSecret))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
