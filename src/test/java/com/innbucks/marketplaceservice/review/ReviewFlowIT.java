package com.innbucks.marketplaceservice.review;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verified-purchase review lifecycle against real Postgres: only a
 * buyer with a PAID order whose parcel was DELIVERED may review, aggregates
 * move atomically with every write, the public read anonymizes the reviewer,
 * and SUPER_ADMIN moderation removal decrements + audits. The V5 unique index,
 * the bulk aggregate UPDATE and the order ⋈ item ⋈ fulfilment gate run against
 * real SQL here — mocked-repo tests can't prove any of them.
 */
class ReviewFlowIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    /** V19: a listing sold by size, the XL at a price of its own. */
    private static final String OPTIONS_LISTING_BODY = """
            {
              "title": "Cotton Crew Tee",
              "description": "100% cotton, pre-shrunk",
              "categoryCode": "other",
              "priceCents": 1999,
              "options": ["Size"],
              "variants": [
                { "values": ["M"], "stockQty": 4 },
                { "values": ["XL"], "priceCents": 2299, "stockQty": 6 }
              ]
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private ListingRepository listingRepository;

    private UUID merchantId;
    private UUID buyerUuid;
    private String merchantToken;
    private String buyerToken;
    private String adminToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        buyerUuid = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        buyerToken = TestJwts.customer(buyerUuid, jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
    }

    private static final String REQUIRES_DELIVERY =
            "You can review this item once your order of it has been delivered";

    @Test
    void verifiedPurchaseReviewLifecycle() throws Exception {
        String listingId = createActiveListing();
        receivedOrderFor(listingId, buyerToken);

        // --- Unverified buyer (no paid order): 403, THE gate ---------------
        String strangerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"never bought it\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review_requires_purchase"))
                .andExpect(jsonPath("$.message").value(REQUIRES_DELIVERY));

        // --- Buyer who received it reviews: 201, comment sanitized, order provenance ---
        String created = mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"Bright <script>x</script> lantern\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.comment").value("Bright  lantern"))
                .andExpect(jsonPath("$.data.orderId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String reviewId = JsonPath.read(created, "$.data.id");

        // Denormalized aggregates moved atomically with the insert.
        Listing afterCreate = listingRepository.findById(UUID.fromString(listingId)).orElseThrow();
        assertThat(afterCreate.getRatingSum()).isEqualTo(5);
        assertThat(afterCreate.getRatingCount()).isEqualTo(1);

        // ...and surface on the public catalog read with zero extra queries.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ratingAvg").value(5.0))
                .andExpect(jsonPath("$.data.reviewCount").value(1));

        // --- Duplicate: 409 (one review per buyer per listing) --------------
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_already_exists"));

        // --- Edit via PUT /mine: aggregates absorb the delta ----------------
        mockMvc.perform(put("/marketplace/listings/{id}/reviews/mine", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3,\"comment\":\"Battery faded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(3));
        Listing afterEdit = listingRepository.findById(UUID.fromString(listingId)).orElseThrow();
        assertThat(afterEdit.getRatingSum()).isEqualTo(3);
        assertThat(afterEdit.getRatingCount()).isEqualTo(1);

        // --- Public read: anonymized handle, newest first, no buyer uuid ----
        String expectedHandle = ReviewService.handleFor(buyerUuid);
        String publicPage = mockMvc.perform(get("/marketplace/catalog/{id}/reviews", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].rating").value(3))
                .andExpect(jsonPath("$.data.items[0].reviewerName").value("Verified buyer"))
                .andExpect(jsonPath("$.data.items[0].reviewerHandle").value(expectedHandle))
                .andReturn().getResponse().getContentAsString();
        assertThat(publicPage).doesNotContain(buyerUuid.toString());

        // --- Merchant-level aggregate ---------------------------------------
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}/rating", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ratingAvg").value(3.0))
                .andExpect(jsonPath("$.data.reviewCount").value(1));

        // --- Admin moderation delete: decrements + audits adminRemoval ------
        mockMvc.perform(delete("/marketplace/listings/{id}/reviews/{reviewId}", listingId, reviewId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Review deleted"));
        Listing afterDelete = listingRepository.findById(UUID.fromString(listingId)).orElseThrow();
        assertThat(afterDelete.getRatingSum()).isZero();
        assertThat(afterDelete.getRatingCount()).isZero();
        Integer auditRows = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                 WHERE event_type = 'REVIEW_DELETED'
                   AND target_id = ?
                   AND metadata LIKE '%"adminRemoval":true%'
                """, Integer.class, reviewId);
        assertThat(auditRows).isEqualTo(1);

        // The buyer's review is gone — a fresh edit 404s.
        mockMvc.perform(put("/marketplace/listings/{id}/reviews/mine", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("review_not_found"));
    }

    @Test
    void anotherCustomerCannotDeleteSomeoneElsesReview() throws Exception {
        String listingId = createActiveListing();
        receivedOrderFor(listingId, buyerToken);
        String created = mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reviewId = JsonPath.read(created, "$.data.id");

        String strangerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(delete("/marketplace/listings/{id}/reviews/{reviewId}", listingId, reviewId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review_not_owned"));

        // A PENDING (unpaid) order does NOT qualify a reviewer.
        String pendingBuyer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + pendingBuyer)
                        .header("Idempotency-Key", "review-it-pending-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + pendingBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review_requires_purchase"));
    }

    @Test
    @DisplayName("A buyer who bought ONE OPTION of a listing may review the listing itself: the "
            + "verified-purchase gate keys on the parent listing an option line records")
    void buyingAnOptionQualifiesAReviewOfTheParentListing() throws Exception {
        String listingId = createActiveListing(OPTIONS_LISTING_BODY);
        String extraLarge = jdbc.queryForObject("""
                SELECT id::text FROM listing_variant
                 WHERE listing_id = ?::uuid AND option1_value = 'XL'""", String.class, listingId);
        String orderId = payOrder("{\"listingId\":\"%s\",\"quantity\":1,\"variantId\":\"%s\"}"
                .formatted(listingId, extraLarge), buyerToken);
        confirmReceived(orderId, merchantId, buyerToken);

        // The paid line is the OPTION, at its own price - and it names the
        // parent listing, which is what the gate queries.
        assertThat(jdbc.queryForObject(
                "SELECT listing_id::text FROM market_order_item WHERE order_id = ?::uuid",
                String.class, orderId)).isEqualTo(listingId);
        assertThat(jdbc.queryForObject(
                "SELECT variant_label FROM market_order_item WHERE order_id = ?::uuid",
                String.class, orderId)).isEqualTo("XL");
        assertThat(jdbc.queryForObject(
                "SELECT unit_price_cents FROM market_order_item WHERE order_id = ?::uuid",
                Long.class, orderId)).isEqualTo(2299L);

        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"Fits well\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.orderId").value(orderId));

        // The review lands on the parent's aggregates - there is one product
        // page, not one per size.
        Listing reviewed = listingRepository.findById(UUID.fromString(listingId)).orElseThrow();
        assertThat(reviewed.getRatingSum()).isEqualTo(4);
        assertThat(reviewed.getRatingCount()).isEqualTo(1);
        mockMvc.perform(get("/marketplace/catalog/{id}/reviews", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].rating").value(4));
    }

    // ------------------------------------------------------------------
    // The gate follows the PARCEL, not the payment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Paid is not received: PREPARING and DISPATCHED are refused, the buyer's own "
            + "receipt confirmation opens the review")
    void aPaidParcelIsReviewableOnlyOnceDelivered() throws Exception {
        String listingId = createActiveListing();
        String orderId = payOrderFor(listingId, buyerToken);

        // PREPARING: the money has moved, the goods have not.
        assertReviewRefused(listingId, buyerToken);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch",
                        fulfilmentIdOf(orderId, merchantId))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        // DISPATCHED: on its way (or on the shelf), still not in the buyer's hands.
        assertReviewRefused(listingId, buyerToken);

        confirmReceived(orderId, merchantId, buyerToken);
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
    }

    @Test
    @DisplayName("A buyer who cancelled their parcel before dispatch cannot review what they "
            + "were refunded for")
    void aBuyerCancelledParcelDoesNotQualify() throws Exception {
        String listingId = createActiveListing();
        String orderId = payOrderFor(listingId, buyerToken);

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        orderId, fulfilmentIdOf(orderId, merchantId))
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ordered the wrong one\"}"))
                .andExpect(status().isOk());
        assertThat(parcelStatus(orderId, merchantId)).isEqualTo("UNFULFILLED");

        assertReviewRefused(listingId, buyerToken);
    }

    @Test
    @DisplayName("A parcel the seller declined cannot be reviewed - the seller is not rated for "
            + "goods they never shipped")
    void aSellerDeclinedParcelDoesNotQualify() throws Exception {
        String listingId = createActiveListing();
        String orderId = payOrderFor(listingId, buyerToken);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable",
                        fulfilmentIdOf(orderId, merchantId))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNFULFILLED"));

        assertReviewRefused(listingId, buyerToken);
    }

    @Test
    @DisplayName("A COLLECTION handed over against a redeemed code qualifies the buyer")
    void aRedeemedCollectionCodeQualifies() throws Exception {
        String listingId = createActiveListing();
        String orderId = payOrderFor(listingId, buyerToken);
        String fulfilmentId = fulfilmentIdOf(orderId, merchantId);
        assertThat(jdbc.queryForObject(
                "SELECT delivery_method FROM market_order WHERE id = ?::uuid",
                String.class, orderId)).isEqualTo("COLLECTION");

        String minted = mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(minted, "$.data.code");
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.deliveredBy").value("RECIPIENT"));

        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
    }

    @Test
    @DisplayName("A courier DELIVERY the seller closed on their own word qualifies too - "
            + "DELIVERED is DELIVERED, whoever closed it")
    void aSellerMarkedDeliveryQualifies() throws Exception {
        String listingId = createActiveListing("""
                {
                  "title": "Solar Lantern 20W",
                  "description": "Portable solar lantern with 12h battery",
                  "categoryCode": "electronics",
                  "priceCents": 1550,
                  "stockQty": 10,
                  "deliveryTowns": [{ "townCode": "harare", "feeCents": 0 }]
                }""");
        String address = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","recipientName":"Tariro Moyo",
                                 "recipientMsisdn":"0771234567","line1":"14 Samora Machel Ave",
                                 "city":"Harare"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = payOrderBody("""
                {"buyerMsisdn":"+263771234567",
                 "items":[{"listingId":"%s","quantity":1}],
                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                .formatted(listingId, JsonPath.<String>read(address, "$.data.id")), buyerToken);
        String fulfilmentId = fulfilmentIdOf(orderId, merchantId);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        assertReviewRefused(listingId, buyerToken);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.deliveredBy").value("MERCHANT"));

        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
    }

    @Test
    @DisplayName("Two sellers, one order: only the seller whose parcel arrived can be reviewed - "
            + "the gate joins on the line's snapshot merchant")
    void inATwoSellerOrderOnlyTheDeliveredSellersListingQualifies() throws Exception {
        UUID otherMerchantId = UUID.randomUUID();
        String otherMerchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), otherMerchantId, jwtSecret);
        String delivered = createActiveListing();
        String undelivered = createActiveListing(LISTING_BODY, otherMerchantToken);

        String orderId = payOrder("""
                {"listingId":"%s","quantity":1},{"listingId":"%s","quantity":1}"""
                .formatted(delivered, undelivered), buyerToken);
        confirmReceived(orderId, merchantId, buyerToken);
        assertThat(parcelStatus(orderId, merchantId)).isEqualTo("DELIVERED");
        assertThat(parcelStatus(orderId, otherMerchantId)).isEqualTo("PREPARING");

        // Same PAID order, same buyer - but the other seller's goods have not
        // arrived, and the first parcel's delivery says nothing about them.
        assertReviewRefused(undelivered, buyerToken);
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", delivered)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
    }

    @Test
    @DisplayName("A review written before the delivery rule stays its author's: edit and delete "
            + "never re-run the gate")
    void anExistingReviewIsNeverRetroactivelyLocked() throws Exception {
        String listingId = createActiveListing();
        String orderId = payOrderFor(listingId, buyerToken);
        assertThat(parcelStatus(orderId, merchantId)).isEqualTo("PREPARING");

        // What the old paid-only gate let through: a review on a parcel that
        // never arrived, with the aggregates it moved.
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO listing_review
                    (id, listing_id, merchant_id, buyer_uuid, order_id, rating, comment,
                     created_at, updated_at)
                VALUES (?, ?::uuid, ?, ?, ?::uuid, 5, 'written under the old gate', now(), now())
                """, reviewId, listingId, merchantId, buyerUuid, orderId);
        jdbc.update("UPDATE listing SET rating_sum = 5, rating_count = 1 WHERE id = ?::uuid",
                listingId);
        assertReviewRefused(listingId, TestJwts.customer(UUID.randomUUID(), jwtSecret));

        mockMvc.perform(put("/marketplace/listings/{id}/reviews/mine", listingId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":2,\"comment\":\"never arrived\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(2));
        mockMvc.perform(delete("/marketplace/listings/{id}/reviews/{reviewId}", listingId, reviewId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());
        Listing after = listingRepository.findById(UUID.fromString(listingId)).orElseThrow();
        assertThat(after.getRatingSum()).isZero();
        assertThat(after.getRatingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Plumbing (the OrderFlowIT shapes)
    // ------------------------------------------------------------------

    private String createActiveListing() throws Exception {
        return createActiveListing(LISTING_BODY);
    }

    private String createActiveListing(String body) throws Exception {
        return createActiveListing(body, merchantToken);
    }

    private String createActiveListing(String body, String merchantToken) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
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

    /** Buyer orders one unit and the payments service confirms it PAID over
     *  the internal S2S surface. Paid is NOT reviewable yet — the parcel is
     *  PREPARING. Returns the order id. */
    private String payOrderFor(String listingId, String customerToken) throws Exception {
        return payOrder("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId), customerToken);
    }

    /** {@link #payOrderFor} and then the buyer confirms receipt of the
     *  parcel — minting review eligibility. */
    private void receivedOrderFor(String listingId, String customerToken) throws Exception {
        confirmReceived(payOrderFor(listingId, customerToken), merchantId, customerToken);
    }

    /** The buyer's own "I received it" on {@code merchant}'s parcel of the order. */
    private void confirmReceived(String orderId, UUID merchant, String customerToken) throws Exception {
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        orderId, fulfilmentIdOf(orderId, merchant))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        assertThat(parcelStatus(orderId, merchant)).isEqualTo("DELIVERED");
    }

    private String fulfilmentIdOf(String orderId, UUID merchant) {
        return jdbc.queryForObject("""
                SELECT id::text FROM order_fulfilment
                 WHERE order_id = ?::uuid AND merchant_id = ?""", String.class, orderId, merchant);
    }

    private String parcelStatus(String orderId, UUID merchant) {
        return jdbc.queryForObject("""
                SELECT status FROM order_fulfilment
                 WHERE order_id = ?::uuid AND merchant_id = ?""", String.class, orderId, merchant);
    }

    private void assertReviewRefused(String listingId, String customerToken) throws Exception {
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review_requires_purchase"))
                .andExpect(jsonPath("$.message").value(REQUIRES_DELIVERY));
    }

    /** {@link #payOrderFor} for any one line ({@code itemJson}, e.g. naming an
     *  option); returns the paid order's id. */
    private String payOrder(String itemJson, String customerToken) throws Exception {
        return payOrderBody("""
                {"buyerMsisdn":"+263771234567","items":[%s]}"""
                .formatted(itemJson), customerToken);
    }

    /** Places {@code orderBody} as-is and confirms it PAID; returns the order id. */
    private String payOrderBody(String orderBody, String customerToken) throws Exception {
        String createdOrder = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "review-it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderRef = JsonPath.read(createdOrder, "$.data.orderRef");
        int totalCents = JsonPath.read(createdOrder, "$.data.totalCents");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":%d}"
                                .formatted(UUID.randomUUID(), totalCents)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        return JsonPath.read(createdOrder, "$.data.id");
    }
}
