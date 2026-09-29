package com.innbucks.marketplaceservice.seller;

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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A seller turning collection off (V20), end to end over the real security
 * chain and a real Postgres, with the cell switch ON: the setting and its
 * operator override, the refusal that names items on sale which could only be
 * collected, the listing gates that keep a delivery-only seller's items
 * deliverable, and the catalogue — cards, profile and the {@code collectsIn} /
 * {@code availableIn} filters against real SQL — no longer offering
 * collection from them.
 *
 * <p>Its own class because the switch is a property: the default context runs
 * with it OFF, which {@link CollectionToggleDefaultOffIT} pins.
 */
// The SAME property set as MixedBasketFlowIT, in the same order, so the two
// share one cached context: every extra context holds its own connection pool
// against the one test Postgres, and one too many is "too many clients". Both
// switches on is also the configuration a cell with delivery-only sellers
// should run (see DeliveryOnlyPolicy's boot WARN).
@TestPropertySource(properties = {
        "marketplace.delivery.delivery-only-sellers-enabled=true",
        "marketplace.delivery.per-seller-methods-enabled=true"
})
class CollectionToggleFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    private static final String HARARE_COUNTER = """
            {"name":"Avondale shop","townCode":"harare","line1":"14 Samora Machel Ave"}""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private String merchantToken;
    private String adminToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
    }

    // ------------------------------------------------------------------
    // The setting, and the catalogue that follows it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A seller reads the setting, keeps it (no-op), turns collection off and on, and "
            + "the catalogue follows: cards, profile and the town filters")
    void theSellerTurnsCollectionOffAndBackOn() throws Exception {
        // Never changed, no record: collects. Reading registers nobody.
        setting(merchantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Collection setting"))
                .andExpect(jsonPath("$.data.collectionEnabled").value(true))
                .andExpect(jsonPath("$.data.updatedAt", nullValue()));
        // Keeping it on changes nothing: still no record, nothing audited.
        change(merchantToken, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(true))
                .andExpect(jsonPath("$.data.updatedAt", nullValue()));
        assertThat(sellerRows()).isZero();
        assertThat(auditCount("SELLER_REGISTERED")).isZero();
        assertThat(auditCount("SELLER_COLLECTION_CHANGED")).isZero();

        // A deliverable item on sale, and a counter in Harare.
        String listingId = publishListing(merchantToken, "[{\"townCode\":\"harare\",\"feeCents\":300}]");
        mockMvc.perform(post("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HARARE_COUNTER))
                .andExpect(status().isCreated());
        browse("collectsIn", "harare").andExpect(jsonPath("$.data.totalItems").value(1));

        // Off.
        change(merchantToken, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Collection setting saved"))
                .andExpect(jsonPath("$.data.collectionEnabled").value(false))
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
        assertThat(jdbc.queryForObject("""
                SELECT collection_enabled FROM marketplace_seller WHERE merchant_id = ?::uuid""",
                Boolean.class, merchantId.toString())).isFalse();
        assertThat(auditCount("SELLER_COLLECTION_CHANGED")).isEqualTo(1);
        String metadata = jdbc.queryForObject("""
                SELECT metadata FROM audit_events WHERE event_type = 'SELLER_COLLECTION_CHANGED'""",
                String.class);
        assertThat(metadata).contains("\"collectionEnabled\":false")
                .contains("\"previous\":true").contains("\"bySeller\":true");
        setting(merchantToken).andExpect(jsonPath("$.data.collectionEnabled").value(false));

        // The catalogue: the card says so and names no collection town...
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(false))
                .andExpect(jsonPath("$.data.collectionTowns", hasSize(0)))
                .andExpect(jsonPath("$.data.deliverable").value(true));
        // ...the profile shows no points (kept, but hidden)...
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(false))
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(0)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seller_collection_point WHERE merchant_id = ?::uuid""",
                Integer.class, merchantId.toString())).isEqualTo(1);
        // ...and against real SQL, collectsIn no longer finds them while the
        // delivery arm of availableIn still does.
        browse("collectsIn", "harare").andExpect(jsonPath("$.data.totalItems").value(0));
        browse("availableIn", "harare")
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(listingId));
        browse("deliversTo", "harare").andExpect(jsonPath("$.data.totalItems").value(1));
        // The operator sees it too.
        mockMvc.perform(get("/marketplace/admin/sellers/{id}/collection", merchantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(false));
        mockMvc.perform(get("/marketplace/admin/sellers")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].collectionEnabled").value(false));

        // Back on: never gated, and the points come back.
        change(merchantToken, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(true));
        browse("collectsIn", "harare").andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", merchantId))
                .andExpect(jsonPath("$.data.collectionEnabled").value(true))
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(1)));
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.collectionEnabled").value(true))
                .andExpect(jsonPath("$.data.collectionTowns[0].townCode").value("harare"));
        assertThat(auditCount("SELLER_COLLECTION_CHANGED")).isEqualTo(2);
    }

    @Test
    @DisplayName("A seller whose record does not exist yet reads as collecting on the public "
            + "surfaces, and never 404s")
    void anUnknownSellerCollects() throws Exception {
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(true))
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(0)));
    }

    // ------------------------------------------------------------------
    // Stranded items, and the listing gates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Items on sale with no delivery town are a 409 naming them - nothing is "
            + "changed - and once off, the listing gates keep every item on sale deliverable")
    void strandedItemsAreNamedAndTheGatesHold() throws Exception {
        String townless = publishListing(merchantToken, "[]");
        String deliverable = publishListing(merchantToken,
                "[{\"townCode\":\"bulawayo\",\"feeCents\":500}]");
        // A townless DRAFT is not on sale, so it is not in the way.
        String draft = createListing(merchantToken, "[]");

        change(merchantToken, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collection_required"))
                .andExpect(jsonPath("$.message").value("Some of your items on sale can only be "
                        + "collected - add delivery towns to them or take them off sale first"))
                .andExpect(jsonPath("$.data.listings", hasSize(1)))
                .andExpect(jsonPath("$.data.listings[0].id").value(townless))
                .andExpect(jsonPath("$.data.listings[0].title").value("Solar Lantern 20W"))
                .andExpect(jsonPath("$.data.truncated").value(false));
        // Refused, never fixed for them: still collecting, the item still on sale.
        assertThat(jdbc.queryForObject("""
                SELECT collection_enabled FROM marketplace_seller WHERE merchant_id = ?::uuid""",
                Boolean.class, merchantId.toString())).isTrue();
        assertThat(listingStatus(townless)).isEqualTo("ACTIVE");
        assertThat(auditCount("SELLER_COLLECTION_CHANGED")).isZero();

        // Take it off sale, and the switch goes through.
        setStatus(townless, "INACTIVE").andExpect(status().isOk());
        change(merchantToken, false).andExpect(status().isOk());

        // Publish gate: a delivery-only seller's townless item cannot go on sale.
        setStatus(townless, "ACTIVE")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("delivery_towns_required"))
                .andExpect(jsonPath("$.message").value("This seller only delivers - add at least "
                        + "one delivery town before putting this item on sale"));
        assertThat(listingStatus(townless)).isEqualTo("INACTIVE");
        // ...nor the DRAFT...
        uploadImage(draft);
        setStatus(draft, "ACTIVE").andExpect(status().isUnprocessableContent());
        // ...until it delivers somewhere.
        update(townless, "[{\"townCode\":\"harare\",\"feeCents\":300}]").andExpect(status().isOk());
        setStatus(townless, "ACTIVE").andExpect(status().isOk());

        // Clearing gate: an item on sale cannot lose its last town...
        update(deliverable, "[]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("delivery_towns_required"))
                .andExpect(jsonPath("$.message").value(
                        "This seller only delivers - an item on sale needs at least one delivery town"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM listing_delivery_town WHERE listing_id = ?::uuid""",
                Integer.class, deliverable)).isEqualTo(1);
        // ...but one taken off sale can, and a replace is always fine.
        update(townless, "[{\"townCode\":\"mutare\",\"feeCents\":800}]").andExpect(status().isOk());
        setStatus(deliverable, "INACTIVE").andExpect(status().isOk());
        update(deliverable, "[]").andExpect(status().isOk());

        // Only the items on sale are in the way: none now, so re-disabling
        // after a re-enable goes straight through.
        change(merchantToken, true).andExpect(status().isOk());
        change(merchantToken, false).andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/catalog").param("availableIn", "mutare"))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(townless)));
    }

    // ------------------------------------------------------------------
    // Checkout follows the setting; an order already paid does not
    // ------------------------------------------------------------------

    @Test
    @DisplayName("After the opt-out, checkout refuses collection from this seller (quote 200 "
            + "naming the line, order 422 reserving nothing, cart untouched) - while a "
            + "collection PAID before it still mints a code and closes")
    void checkoutFollowsTheSettingButAPaidCollectionCompletes() throws Exception {
        String listingId = publishListing(merchantToken, "[{\"townCode\":\"harare\",\"feeCents\":300}]");
        String customer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);

        // --- A collection ordered and paid while the seller still collects ---
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", "toggle-before-opt-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"COLLECTION"}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}".formatted(orderRef)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");
        assertThat(jdbc.queryForObject("SELECT delivery_method FROM order_fulfilment WHERE id = ?::uuid",
                String.class, fulfilmentId)).isEqualTo("COLLECTION");

        // --- The seller opts out ---------------------------------------------
        change(merchantToken, false).andExpect(status().isOk());

        // --- New checkouts follow the setting --------------------------------
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/cart").header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                .andExpect(jsonPath("$.data.items[0].issue").doesNotExist());
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"COLLECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("COLLECTION_NOT_OFFERED"))
                .andExpect(jsonPath("$.data.rejections[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.sellers[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.sellers[0].availableMethods", hasSize(1)))
                .andExpect(jsonPath("$.data.sellers[0].availableMethods[0]").value("DELIVERY"));
        int stockBefore = listingStock(listingId);
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", "toggle-after-opt-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"COLLECTION\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("collection_not_offered"))
                .andExpect(jsonPath("$.message")
                        .value("Solar Lantern 20W is delivery only. Choose delivery or remove it."));
        assertThat(listingStock(listingId)).isEqualTo(stockBefore);

        // --- ...but the collection already paid for completes ----------------
        // The parcel snapshot, not the live setting, decides: a seller opting
        // out must not strand goods a buyer has paid to collect.
        String minted = mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(minted, "$.data.code");
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));
    }

    // ------------------------------------------------------------------
    // The operator override
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An operator turns a seller's collection off on their behalf - registered only "
            + "then, and audited as the operator")
    void theOperatorOverride() throws Exception {
        mockMvc.perform(get("/marketplace/admin/sellers/{id}/collection", merchantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(true))
                .andExpect(jsonPath("$.data.updatedAt", nullValue()));
        assertThat(sellerRows()).isZero();

        mockMvc.perform(put("/marketplace/admin/sellers/{id}/collection", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(false));

        assertThat(sellerRows()).isEqualTo(1);
        assertThat(auditCount("SELLER_REGISTERED")).isEqualTo(1);
        String metadata = jdbc.queryForObject("""
                SELECT metadata FROM audit_events WHERE event_type = 'SELLER_COLLECTION_CHANGED'""",
                String.class);
        assertThat(metadata).contains("\"bySeller\":false");
        // The seller sees what the operator did.
        setting(merchantToken).andExpect(jsonPath("$.data.collectionEnabled").value(false));
    }

    // ------------------------------------------------------------------
    // Scope and validation, with SPECIFIC codes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Anonymous callers are 401; customers, merchants on the operator surface and a "
            + "role without a selling organization are 403; a missing value is 400")
    void scopeAndValidation() throws Exception {
        mockMvc.perform(get("/marketplace/sellers/me/collection"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/marketplace/sellers/me/collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":false}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/admin/sellers/{id}/collection", merchantId))
                .andExpect(status().isUnauthorized());

        String customer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(put("/marketplace/sellers/me/collection")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // Naming a merchant is the operator's surface only: a seller cannot
        // switch a competitor's collection off.
        mockMvc.perform(put("/marketplace/admin/sellers/{id}/collection", UUID.randomUUID())
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/marketplace/sellers/me/collection")
                        .header("Authorization", "Bearer " + TestJwts.merchantAdminWithoutMerchant(
                                UUID.randomUUID(), jwtSecret)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // A missing value is refused, never read as "off".
        change(merchantToken, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.collectionEnabled").value("must not be null"));
        assertThat(sellerRows()).isZero();
    }

    // ------------------------------------------------------------------

    private ResultActions setting(String token) throws Exception {
        return mockMvc.perform(get("/marketplace/sellers/me/collection")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions change(String token, boolean enabled) throws Exception {
        return change(token, "{\"collectionEnabled\":" + enabled + "}");
    }

    private ResultActions change(String token, String body) throws Exception {
        return mockMvc.perform(put("/marketplace/sellers/me/collection")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions browse(String param, String value) throws Exception {
        return mockMvc.perform(get("/marketplace/catalog").param(param, value));
    }

    private ResultActions setStatus(String listingId, String status) throws Exception {
        return mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                .header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions update(String listingId, String deliveryTowns) throws Exception {
        return mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                .header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Solar Lantern 20W","categoryCode":"electronics",
                         "priceCents":1550,"stockQty":10,"deliveryTowns":%s}"""
                        .formatted(deliveryTowns)));
    }

    private int sellerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM marketplace_seller WHERE merchant_id = ?::uuid",
                Integer.class, merchantId.toString());
    }

    private int auditCount(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = ?",
                Integer.class, type);
    }

    private int listingStock(String listingId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }

    private String listingStatus(String listingId) {
        return jdbc.queryForObject("SELECT status FROM listing WHERE id = ?::uuid",
                String.class, listingId);
    }

    private String createListing(String token, String deliveryTowns) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10,"deliveryTowns":%s}"""
                                .formatted(deliveryTowns)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.id");
    }

    private void uploadImage(String listingId) throws Exception {
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
    }

    private String publishListing(String token, String deliveryTowns) throws Exception {
        String listingId = createListing(token, deliveryTowns);
        uploadImage(listingId);
        setStatus(listingId, "ACTIVE").andExpect(status().isOk());
        return listingId;
    }
}
