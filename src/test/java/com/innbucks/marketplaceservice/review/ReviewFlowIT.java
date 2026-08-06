package com.innbucks.marketplaceservice.review;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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
 * buyer with a PAID order may review, aggregates move atomically with every
 * write, the public read anonymizes the reviewer, and SUPER_ADMIN moderation
 * removal decrements + audits. The V5 unique index and the bulk aggregate
 * UPDATE run against real SQL here — mocked-repo tests can't prove either.
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

    @Test
    void verifiedPurchaseReviewLifecycle() throws Exception {
        String listingId = createActiveListing();
        payOrderFor(listingId, buyerToken);

        // --- Unverified buyer (no paid order): 403, THE gate ---------------
        String strangerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/listings/{id}/reviews", listingId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"never bought it\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("review_requires_purchase"));

        // --- Paid buyer reviews: 201, comment sanitized, order provenance ---
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
        payOrderFor(listingId, buyerToken);
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

    // ------------------------------------------------------------------
    // Plumbing (the OrderFlowIT shapes)
    // ------------------------------------------------------------------

    private String createActiveListing() throws Exception {
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

    /** Buyer orders one unit and the payments service confirms it PAID over
     *  the internal S2S surface — minting review eligibility. */
    private void payOrderFor(String listingId, String customerToken) throws Exception {
        String orderBody = """
                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                .formatted(listingId);
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
    }
}
