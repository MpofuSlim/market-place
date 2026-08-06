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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
 * End-to-end flows over real HTTP semantics (MockMvc through the real
 * SecurityFilterChain) and a real Postgres:
 *
 * <ul>
 *   <li><b>Listing image GALLERY (V3)</b> — multi-image multipart create
 *       (primary + additional parts), per-image public serving, primary
 *       replace/delete with promotion, the atomic primary swap, the 10-image
 *       cap, magic-byte validation (GIF and spoofed content types rejected),
 *       anonymous serving with the stored Content-Type + nosniff + public 1h
 *       cache, DRAFT previews (status-independent serving), and the PUBLISH
 *       GATE (422 primary_image_required on DRAFT→ACTIVE without a
 *       primary).</li>
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
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    /** Real PNG signature (89 50 4E 47 0D 0A 1A 0A) + filler bytes. */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7, 6, 5};

    /** A second, distinguishable PNG (same signature, different payload). */
    private static final byte[] PNG_BYTES_2 =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 1, 2, 3, 5};

    /** A third PNG for the extras of the multi-image create. */
    private static final byte[] PNG_BYTES_3 =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 4, 4, 4, 4, 4};

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
                .andExpect(jsonPath("$.data.imageUrls").isEmpty())
                .andExpect(jsonPath("$.data.categoryCode").value("electronics"))
                .andExpect(jsonPath("$.data.categoryName").value("Electronics"))
                .andExpect(jsonPath("$.data.condition").value("NEW"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    /** Uploads the primary image so the publish gate lets the listing go ACTIVE. */
    private void uploadPrimary(String listingId, String token) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Gallery
    // ------------------------------------------------------------------

    @Test
    void imageLifecycle_uploadServePubliclyWhileDraftThenDelete() throws Exception {
        String listingId = createDraftListing(merchantToken);

        // Upload (multipart PUT, part name "image") — becomes the PRIMARY;
        // the response carries imageUrl AND the per-image gallery URL.
        String uploaded = mockMvc.perform(
                        multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                                .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.imageUrl")
                        .value("/marketplace/catalog/" + listingId + "/image"))
                .andExpect(jsonPath("$.data.imageUrls.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String imageUrl = JsonPath.read(uploaded, "$.data.imageUrls[0]");

        // Anonymous read WHILE THE LISTING IS STILL DRAFT — deliberate: the
        // owner needs the preview and UUIDs are unguessable. Raw bytes, the
        // stored Content-Type, nosniff, and a public 1h cache — on BOTH the
        // primary URL and the per-image gallery URL.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(content().bytes(PNG_BYTES));
        mockMvc.perform(get(imageUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG_BYTES));

        // The merchant's own list view exposes the URLs without loading bytes.
        mockMvc.perform(get("/marketplace/listings/mine")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].imageUrl")
                        .value("/marketplace/catalog/" + listingId + "/image"))
                .andExpect(jsonPath("$.data.items[0].imageUrls[0]").value(imageUrl));

        // Replacing the primary keeps the SAME per-image URL (in-place
        // replace) but serves the new bytes.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo2.png", "image/png", PNG_BYTES_2))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls[0]").value(imageUrl));
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES_2));

        // Delete-primary with no survivors empties the gallery; the public
        // URLs then 404 and imageUrl returns to null.
        mockMvc.perform(delete("/marketplace/listings/{id}/image", listingId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").isEmpty())
                .andExpect(jsonPath("$.data.imageUrls").isEmpty());
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
        mockMvc.perform(get(imageUrl))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
    }

    @Test
    void multiImageCreate_primaryPlusTwoExtrasInOneMultipartRequest() throws Exception {
        // One-shot create with parts: listing (JSON) + image (primary) +
        // images (2 extras). Gallery order: primary first, then append order.
        String body = mockMvc.perform(multipart("/marketplace/listings")
                        .file(new MockMultipartFile("listing", "", "application/json",
                                LISTING_BODY.getBytes()))
                        .file(new MockMultipartFile("image", "main.png", "image/png", PNG_BYTES))
                        .file(new MockMultipartFile("images", "side.png", "image/png", PNG_BYTES_2))
                        .file(new MockMultipartFile("images", "back.png", "image/png", PNG_BYTES_3))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.imageUrls.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(body, "$.data.id");
        List<String> imageUrls = JsonPath.read(body, "$.data.imageUrls");
        assertThat((String) JsonPath.read(body, "$.data.imageUrl"))
                .isEqualTo("/marketplace/catalog/" + listingId + "/image");

        // The primary URL serves the 'image' part's bytes; each gallery URL
        // serves its own image, in upload order.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        mockMvc.perform(get(imageUrls.get(0)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        mockMvc.perform(get(imageUrls.get(1)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES_2));
        mockMvc.perform(get(imageUrls.get(2)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES_3));

        // A gallery imageId is only servable under ITS OWN listing — pairing
        // it with another listing id is the same indistinguishable 404.
        String foreignListing = createDraftListing(merchantToken);
        String imageIdPath = imageUrls.get(1)
                .substring(imageUrls.get(1).lastIndexOf("/images/"));
        mockMvc.perform(get("/marketplace/catalog/" + foreignListing + imageIdPath))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
    }

    @Test
    void galleryManagement_addPromoteDeleteWithPromotion() throws Exception {
        String listingId = createDraftListing(merchantToken);
        uploadPrimary(listingId, merchantToken);

        // POST /images appends a NON-primary.
        String added = mockMvc.perform(
                        multipart("/marketplace/listings/{id}/images", listingId)
                                .file(new MockMultipartFile("image", "second.png", "image/png", PNG_BYTES_2))
                                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<String> urls = JsonPath.read(added, "$.data.imageUrls");
        String primaryUrl = urls.get(0);
        String secondUrl = urls.get(1);
        String secondImageId = secondUrl.substring(secondUrl.lastIndexOf('/') + 1);

        // Atomic primary swap: the second image becomes primary and moves to
        // the FRONT of imageUrls; the public primary URL now serves its bytes.
        mockMvc.perform(put("/marketplace/listings/{id}/images/{imageId}/primary",
                        listingId, secondImageId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls[0]").value(secondUrl))
                .andExpect(jsonPath("$.data.imageUrls[1]").value(primaryUrl));
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES_2));

        // Deleting the (new) PRIMARY promotes the survivor — the gallery never
        // sits primary-less while images remain.
        mockMvc.perform(delete("/marketplace/listings/{id}/images/{imageId}",
                        listingId, secondImageId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(1))
                .andExpect(jsonPath("$.data.imageUrls[0]").value(primaryUrl))
                .andExpect(jsonPath("$.data.imageUrl")
                        .value("/marketplace/catalog/" + listingId + "/image"));
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));

        // Deleting an imageId that is not this listing's is 404.
        mockMvc.perform(delete("/marketplace/listings/{id}/images/{imageId}",
                        listingId, UUID.randomUUID())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image_not_found"));
    }

    @Test
    void publishGate_activationRequiresAPrimaryImage() throws Exception {
        String listingId = createDraftListing(merchantToken);

        // Imageless DRAFT -> ACTIVE is refused with the specific 422.
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("primary_image_required"));

        // Non-ACTIVE transitions stay unguarded — drafts may be imageless.
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        // With a primary uploaded the same transition succeeds.
        uploadPrimary(listingId, merchantToken);
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
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
        // sniff refuses what the header would have let through. Same guard on
        // the gallery-add endpoint.
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "fake.png", "image/png",
                                "<html><script>alert(1)</script></html>".getBytes()))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported_image_type"));
        mockMvc.perform(multipart("/marketplace/listings/{id}/images", listingId)
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
                                  "categoryCode": "electronics",
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
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()))
                // Omitted taxonomy fields fall back to their defaults.
                .andExpect(jsonPath("$.data.categoryCode").value("other"))
                .andExpect(jsonPath("$.data.condition").value("NEW"));

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
        // Merchant lists + uploads the primary (publish gate) + activates;
        // the buyer orders.
        String listingId = createDraftListing(merchantToken);
        uploadPrimary(listingId, merchantToken);
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
