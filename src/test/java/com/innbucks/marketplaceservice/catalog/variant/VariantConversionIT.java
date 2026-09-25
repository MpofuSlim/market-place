package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.order.OrderExpirySweeper;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * A listing changing SHAPE underneath the shoppers already holding it (V19),
 * over real HTTP and a real Postgres.
 *
 * <p>Every conversion is a seller's edit that the rest of the platform did not
 * see coming: a pending order reserved units against the old shape, a cart
 * line names an option the seller has just deleted. The rules pinned here are
 * the ones CLAUDE.md states for exactly those moments:
 * <ul>
 *   <li><b>Conversions drop in-flight units, by design.</b> A return of units
 *       reserved under the old shape credits nothing, the seller's new counts
 *       stay the truth, and the drop is metered
 *       {@code marketplace.stock.returns_dropped{reason}} rather than absorbed
 *       silently - and the return is still released exactly once.</li>
 *   <li><b>A removed option never vanishes from a cart.</b> The line stays,
 *       echoing its {@code variantId} with a {@code VARIANT_UNAVAILABLE} issue,
 *       the order is refused with nothing reserved, and the line is still
 *       removable by the id it echoes (there is no FK on
 *       {@code cart_variant_item.variant_id}, on purpose).</li>
 *   <li><b>{@code variants} is an omit-keeps field</b> like
 *       {@code deliveryTowns}: null keeps every option (and ignores
 *       {@code stockQty}), {@code []} converts back to a plain listing and
 *       needs {@code stockQty}.</li>
 * </ul>
 */
class VariantConversionIT extends PostgresTestContainer {

    /** A listing WITHOUT options, 5 in stock, at the price every option below
     *  is sold at - so a conversion changes the shape and nothing else. */
    private static final String PLAIN_BODY = """
            {
              "title": "Cotton Crew Tee",
              "description": "100% cotton, pre-shrunk",
              "categoryCode": "other",
              "priceCents": 1999,
              "stockQty": 5
            }""";

    /** The same product sold by size: M (4) and L (6), 10 in total. */
    private static final String OPTIONS_BODY = """
            {
              "title": "Cotton Crew Tee",
              "description": "100% cotton, pre-shrunk",
              "categoryCode": "other",
              "priceCents": 1999,
              "options": ["Size"],
              "variants": [
                { "values": ["M"], "stockQty": 4 },
                { "values": ["L"], "stockQty": 6 }
              ]
            }""";

    /** The editor's full replace that turns the plain listing into M (4) + L (6). */
    private static final String TO_OPTIONS_PUT = """
            {
              "title": "Cotton Crew Tee",
              "categoryCode": "other",
              "priceCents": 1999,
              "options": ["Size"],
              "variants": [
                { "values": ["M"], "stockQty": 4 },
                { "values": ["L"], "stockQty": 6 }
              ]
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OrderExpirySweeper expirySweeper;

    private String merchantToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        // The payer is the caller: a real CUSTOMER token carries the phone
        // claim, so no order body below needs a buyerMsisdn.
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    // ------------------------------------------------------------------
    // Plain -> options with a pending order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A pending order's units are not credited back once the listing has options: "
            + "on cancel the total stays the options' sum and the drop is metered listing_converted")
    void aCancelAfterConvertingToOptionsDropsTheReturnAndMetersIt() throws Exception {
        String listingId = publish(PLAIN_BODY);
        String orderId = orderPlainLine(listingId, 2, "convert-then-cancel");
        assertThat(listingStock(listingId)).isEqualTo(3);

        convertToOptions(listingId);
        double droppedBefore = droppedReturns("listing_converted");

        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // The two units were held against a shape the listing no longer has.
        // The seller's per-option counts are the truth: nothing is credited to
        // any option, and the derived total still equals their sum.
        assertThat(optionStock(listingId, "M")).isEqualTo(4);
        assertThat(optionStock(listingId, "L")).isEqualTo(6);
        assertThat(listingStock(listingId)).isEqualTo(10);
        assertThat(optionStockSum(listingId)).isEqualTo(10);
        // Visible, never silently absorbed - and counted exactly once.
        assertThat(droppedReturns("listing_converted")).isEqualTo(droppedBefore + 1);
        // Released all the same: the exactly-once flag is set, so nothing can
        // try to return those units again later.
        assertThat(jdbc.queryForObject("SELECT stock_released FROM market_order WHERE id = ?::uuid",
                Boolean.class, orderId)).isTrue();
    }

    @Test
    @DisplayName("The same holds when the pending order EXPIRES through the sweep instead: "
            + "total = the options' sum, one listing_converted drop")
    void anExpiryAfterConvertingToOptionsDropsTheReturnAndMetersIt() throws Exception {
        String listingId = publish(PLAIN_BODY);
        String orderId = orderPlainLine(listingId, 2, "convert-then-expire");

        convertToOptions(listingId);
        double droppedBefore = droppedReturns("listing_converted");

        jdbc.update("UPDATE market_order SET expires_at = now() - interval '5 minutes' WHERE id = ?::uuid",
                orderId);
        expirySweeper.sweep();

        // EXPIRED proves the return ran and did not throw: the sweep swallows a
        // row's exception and would have left it PENDING_PAYMENT.
        assertThat(jdbc.queryForObject("SELECT status FROM market_order WHERE id = ?::uuid",
                String.class, orderId)).isEqualTo("EXPIRED");
        assertThat(listingStock(listingId)).isEqualTo(10);
        assertThat(optionStockSum(listingId)).isEqualTo(10);
        assertThat(droppedReturns("listing_converted")).isEqualTo(droppedBefore + 1);
    }

    // ------------------------------------------------------------------
    // An option removed under a shopper's cart line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An option removed while it sits in a cart: the line stays with its variantId and a "
            + "VARIANT_UNAVAILABLE issue, the order is 422 variant_unavailable having reserved nothing, "
            + "and DELETE ?variantId= still removes it")
    void aRemovedOptionStaysVisibleInTheCartAndIsStillRemovable() throws Exception {
        String listingId = publish(OPTIONS_BODY);
        String large = variantId(listingId, "L");

        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"variantId\":\"%s\",\"quantity\":1}"
                                .formatted(listingId, large)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].variant.label").value("L"))
                .andExpect(jsonPath("$.data.checkoutReady").value(true));

        // The seller drops size L: a replace that keeps only M - matched by its
        // values, with stockQty omitted so M keeps its 4.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                                 "options":["Size"],"variants":[{"values":["M"]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants.length()").value(1))
                .andExpect(jsonPath("$.data.variants[0].label").value("M"))
                .andExpect(jsonPath("$.data.variants[0].stockQty").value(4))
                .andExpect(jsonPath("$.data.stockQty").value(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_variant WHERE id = ?::uuid",
                Integer.class, large)).isZero();

        // The shopper's line is still there - never dropped silently - and it
        // still says which option it was, so the app can find and remove it.
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineCount").value(1))
                .andExpect(jsonPath("$.data.items[0].listingId").value(listingId))
                .andExpect(jsonPath("$.data.items[0].variantId").value(large))
                .andExpect(jsonPath("$.data.items[0].variant").doesNotExist())
                // A VARIANT_* issue keeps the listing: the shopper must still
                // see what they are fixing.
                .andExpect(jsonPath("$.data.items[0].listing.id").value(listingId))
                .andExpect(jsonPath("$.data.items[0].issue.reason").value("VARIANT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].issue.variantId").value(large))
                .andExpect(jsonPath("$.data.items[0].lineTotalCents").value(0))
                .andExpect(jsonPath("$.data.subtotalCents").value(0))
                .andExpect(jsonPath("$.data.checkoutReady").value(false));

        // An order from that cart is refused before any stock is touched.
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "removed-option-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("variant_unavailable"))
                .andExpect(jsonPath("$.message").value(
                        "Listing " + listingId + " variant " + large + " is not available"))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("VARIANT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.rejections[0].variantId").value(large));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order", Integer.class)).isZero();
        assertThat(optionStock(listingId, "M")).isEqualTo(4);
        assertThat(listingStock(listingId)).isEqualTo(4);

        // The removed option cannot be added again ...
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"variantId\":\"%s\",\"quantity\":1}"
                                .formatted(listingId, large)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("variant_not_found"));

        // ... but the line it left behind is removed by the id it echoes.
        mockMvc.perform(delete("/marketplace/cart/items/{listingId}", listingId)
                        .param("variantId", large)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineCount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_variant_item", Integer.class))
                .isZero();
    }

    // ------------------------------------------------------------------
    // variants: [] and variants: null on a listing with options
    // ------------------------------------------------------------------

    @Test
    @DisplayName("variants [] with a stockQty converts a listing with options back to a plain one; "
            + "without stockQty it is refused and nothing changes")
    void anEmptyVariantsListConvertsBackToPlain() throws Exception {
        String listingId = publish(OPTIONS_BODY);

        // [] means the listing will hold its own stock again, so it must say how much.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                                 "variants":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("stock_required"));
        assertThat(optionCount(listingId)).isEqualTo(2);
        assertThat(listingStock(listingId)).isEqualTo(10);
        assertThat(hasVariants(listingId)).isTrue();

        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                                 "variants":[],"stockQty":7}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVariants").value(false))
                .andExpect(jsonPath("$.data.options").isEmpty())
                .andExpect(jsonPath("$.data.variants").isEmpty())
                .andExpect(jsonPath("$.data.stockQty").value(7))
                .andExpect(jsonPath("$.data.maxPriceCents").value(1999));
        assertThat(optionCount(listingId)).isZero();
        assertThat(hasVariants(listingId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT option1_name FROM listing WHERE id = ?::uuid",
                String.class, listingId)).isNull();
        assertThat(listingStock(listingId)).isEqualTo(7);

        // It now sells exactly like any listing without options: a line that
        // names none is fine ...
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":2}".formatted(listingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].variantId").doesNotExist())
                .andExpect(jsonPath("$.data.subtotalCents").value(3998))
                .andExpect(jsonPath("$.data.checkoutReady").value(true));

        // ... and an order reserves from the listing's own stock.
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "back-to-plain-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].variantId").doesNotExist())
                .andExpect(jsonPath("$.data.totalCents").value(3998));
        assertThat(listingStock(listingId)).isEqualTo(5);
    }

    @Test
    @DisplayName("A replace that omits variants keeps every option - its id, stock and own price - "
            + "and ignores stockQty, so an editor built before options can never wipe them")
    void omittedVariantsKeepEveryOptionUnchanged() throws Exception {
        String listingId = publish("""
                {
                  "title": "Cotton Crew Tee",
                  "categoryCode": "other",
                  "priceCents": 1999,
                  "options": ["Size"],
                  "variants": [
                    { "values": ["M"], "stockQty": 4 },
                    { "values": ["L"], "priceCents": 2499, "stockQty": 6 }
                  ]
                }""");
        String medium = variantId(listingId, "M");
        String large = variantId(listingId, "L");

        // A pre-V19 editor: no options, no variants, and the echoed total as stockQty.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Organic Cotton Crew Tee","categoryCode":"other",
                                 "priceCents":1999,"stockQty":999}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Organic Cotton Crew Tee"))
                .andExpect(jsonPath("$.data.hasVariants").value(true))
                .andExpect(jsonPath("$.data.options[0].name").value("Size"))
                .andExpect(jsonPath("$.data.variants.length()").value(2))
                .andExpect(jsonPath("$.data.variants[0].id").value(medium))
                .andExpect(jsonPath("$.data.variants[0].stockQty").value(4))
                .andExpect(jsonPath("$.data.variants[0].priceOverrideCents").doesNotExist())
                .andExpect(jsonPath("$.data.variants[1].id").value(large))
                .andExpect(jsonPath("$.data.variants[1].stockQty").value(6))
                .andExpect(jsonPath("$.data.variants[1].priceOverrideCents").value(2499))
                // The total, not the 999 the old editor echoed back.
                .andExpect(jsonPath("$.data.stockQty").value(10))
                .andExpect(jsonPath("$.data.maxPriceCents").value(2499));
        assertThat(optionCount(listingId)).isEqualTo(2);
        assertThat(optionStock(listingId, "M")).isEqualTo(4);
        assertThat(optionStock(listingId, "L")).isEqualTo(6);
        assertThat(listingStock(listingId)).isEqualTo(10);

        // Kept options still hold the floor rule: a price raised above an own
        // price is a clear 400, never a silent rewrite of the options.
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Organic Cotton Crew Tee","categoryCode":"other",
                                 "priceCents":2999,"stockQty":10}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("variant_price_below_listing_price"));
        assertThat(jdbc.queryForObject("SELECT price_cents FROM listing WHERE id = ?::uuid",
                Long.class, listingId)).isEqualTo(1999L);
        assertThat(optionCount(listingId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Creates the listing and takes it through the publish gate. */
    private String publish(String body) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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

    /** A PENDING_PAYMENT order for a line WITHOUT an option; returns its id. */
    private String orderPlainLine(String listingId, int quantity, String idempotencyKey)
            throws Exception {
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"listingId\":\"%s\",\"quantity\":%d}]}"
                                .formatted(listingId, quantity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.id");
    }

    /** The seller's replace that gives the plain listing options M (4) and L (6). */
    private void convertToOptions(String listingId) throws Exception {
        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TO_OPTIONS_PUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVariants").value(true))
                .andExpect(jsonPath("$.data.variants.length()").value(2))
                // The seller's per-option counts ARE the stock from here on.
                .andExpect(jsonPath("$.data.stockQty").value(10));
    }

    private String variantId(String listingId, String value) {
        return jdbc.queryForObject("""
                SELECT id::text FROM listing_variant
                 WHERE listing_id = ?::uuid AND option1_value = ?""",
                String.class, listingId, value);
    }

    private int optionStock(String listingId, String value) {
        return jdbc.queryForObject("""
                SELECT stock_qty FROM listing_variant
                 WHERE listing_id = ?::uuid AND option1_value = ?""",
                Integer.class, listingId, value);
    }

    private int optionStockSum(String listingId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(stock_qty), 0) FROM listing_variant WHERE listing_id = ?::uuid",
                Integer.class, listingId);
    }

    private int optionCount(String listingId) {
        return jdbc.queryForObject("SELECT count(*) FROM listing_variant WHERE listing_id = ?::uuid",
                Integer.class, listingId);
    }

    private int listingStock(String listingId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }

    private boolean hasVariants(String listingId) {
        return jdbc.queryForObject("SELECT has_variants FROM listing WHERE id = ?::uuid",
                Boolean.class, listingId);
    }

    /** The counter is registered on first use, so "never incremented" reads 0. */
    private double droppedReturns(String reason) {
        Counter counter = meterRegistry.find("marketplace.stock.returns_dropped")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
