package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end flows for the two 2026-08 features, over real HTTP semantics
 * (MockMvc through the real SecurityFilterChain) and a real Postgres:
 *
 * <ul>
 *   <li><b>Listing images</b> — multipart upload with magic-byte validation
 *       (GIF and spoofed content types rejected), anonymous serving with the
 *       stored Content-Type + nosniff + public 1h cache, DRAFT previews
 *       (status-independent serving), delete → 404.</li>
 *   <li><b>SUPER_ADMIN oversight</b> — any-merchant listing management,
 *       on-behalf creation via the request merchantId, all-listings and
 *       all-orders reads, while CUSTOMER owner-masking stays intact.</li>
 * </ul>
 */
class SuperAdminAndImageFlowIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "category": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    /** Real PNG signature (89 50 4E 47 0D 0A 1A 0A) + filler bytes. */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7, 6, 5};

    /** GIF89a signature — the deliberately refused format. */
    private static final byte[] GIF_BYTES =
            {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 2, 3, 4, 5, 6};

    @Value("${jwt.secret}")
    private String jwtSecret;

    private UUID merchantId;
    private String merchantToken;
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
    }

    private String createDraftListing(String token) throws Exception {
        String body = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageUrl").isEmpty())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    @Test
    void imageLifecycle_uploadServePubliclyWhileDraftThenDelete() throws Exception {
        String listingId = createDraftListing(merchantToken);

        // Upload (multipart PUT, part name "image") — response carries imageUrl.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.imageUrl")
                        .value("/marketplace/catalog/" + listingId + "/image"));

        // Anonymous read WHILE THE LISTING IS STILL DRAFT — deliberate: the
        // owner needs the preview and UUIDs are unguessable. Raw bytes, the
        // stored Content-Type, nosniff, and a public 1h cache.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(content().bytes(PNG_BYTES));

        // The merchant's own list view exposes the URL without loading bytes.
        mockMvc.perform(get("/marketplace/listings/mine")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].imageUrl")
                        .value("/marketplace/catalog/" + listingId + "/image"));

        // Delete clears both columns; the public URL then 404s and imageUrl
        // returns to null.
        mockMvc.perform(delete("/marketplace/listings/{id}/image", listingId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").isEmpty());
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
    }

    @Test
    void imageUploadRejectsGifAndSpoofedContentTypes() throws Exception {
        String listingId = createDraftListing(merchantToken);

        // Honest GIF: refused by the content-type allow-list.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "anim.gif", "image/gif", GIF_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported_image_type"));

        // Smuggled payload: image/png declared, HTML bytes — the magic-byte
        // sniff refuses what the header would have let through.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "fake.png", "image/png",
                                "<html><script>alert(1)</script></html>".getBytes()))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported_image_type"));

        // Empty part: 400 image_required (not a 500 from a missing part).
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("image_required"));

        // Nothing stored by any of the rejections.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
    }

    // ------------------------------------------------------------------
    // SUPER_ADMIN oversight
    // ------------------------------------------------------------------

    @Test
    void superAdminManagesAnyMerchantsListing() throws Exception {
        String listingId = createDraftListing(merchantToken);

        // Update another merchant's listing — no merchantId claim on the token.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Solar Lantern 20W (admin-corrected)",
                                  "description": "Portable solar lantern with 12h battery",
                                  "category": "electronics",
                                  "priceCents": 1450,
                                  "stockQty": 10
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Solar Lantern 20W (admin-corrected)"))
                // The listing stays the merchant's — admin edits never re-parent.
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()));

        // ... including its image.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // On-behalf creation: merchantId REQUIRED for admins (no claim) ...
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("merchant_id_required"));
        // ... and honored when present.
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "USB-C Charging Cable 2m",
                                  "priceCents": 450,
                                  "stockQty": 50,
                                  "merchantId": "%s"
                                }""".formatted(merchantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()));

        // A MERCHANT_ADMIN sending someone else's merchantId is refused 422.
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Rogue listing",
                                  "priceCents": 100,
                                  "stockQty": 1,
                                  "merchantId": "%s"
                                }""".formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("merchant_scope_mismatch"));

        // Oversight read: ALL listings (both merchants' rows), filterable.
        mockMvc.perform(get("/marketplace/listings/mine")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(get("/marketplace/listings/mine")
                        .param("merchantId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void superAdminReadsAnyOrderWhileCustomerMaskingHolds() throws Exception {
        // Merchant lists + activates; the buyer orders.
        String listingId = createDraftListing(merchantToken);
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        String orderBody = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "admin-read-flow-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":2}]}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(orderBody, "$.data.id");

        // SUPER_ADMIN: any order by id, all orders, buyer-filtered orders.
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.totalCents").value(3100));
        mockMvc.perform(get("/marketplace/orders")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(orderId));
        mockMvc.perform(get("/marketplace/orders")
                        .param("buyerUuid", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));
        // Garbage filter is a clean 400, never an unfiltered dump.
        mockMvc.perform(get("/marketplace/orders")
                        .param("buyerUuid", "not-a-uuid")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_buyer_uuid"));

        // Owner-masking unchanged for customers: another CUSTOMER sees the
        // same 404 as a nonexistent id.
        String otherCustomer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + otherCustomer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order_not_found"));
    }
}
