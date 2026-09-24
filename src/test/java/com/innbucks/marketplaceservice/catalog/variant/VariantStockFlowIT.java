package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.order.OrderExpirySweeper;
import com.innbucks.marketplaceservice.order.OrderService;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product options (V19) end to end, over the real security chain and a real
 * Postgres: a seller lists one tee in three sizes, the catalogue shows the
 * axes, the options and the price range, a buyer puts two sizes in the cart
 * and orders them, and every way the units can come back — a cancel, an
 * expiry, a seller's decline, a buyer's parcel cancel — returns each size to
 * its OWN stock exactly once, with the listing total following. Then the
 * option label is followed onto every surface an order reaches, and the
 * seller's one-size quick restock is shown leaving the other sizes (and the
 * reservations on them) alone.
 *
 * <p>Every stock assertion is an exact number read back with SQL: the
 * {@code listing_variant} row is the truth for each size, and
 * {@code listing.stock_qty} is the derived total the catalogue reads.
 */
class VariantStockFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    /** The canonical example listing: M and L at the listing price, XL dearer. */
    private static final String TEE = """
            {"title":"Cotton Crew Tee","description":"100% cotton, pre-shrunk",
             "categoryCode":"other","condition":"NEW","city":"Harare","area":"Avondale",
             "priceCents":1999,
             "options":["Size","Colour"],
             "variants":[{"values":["M","Black"],"stockQty":10},
                         {"values":["L","Black"],"stockQty":5},
                         {"values":["XL","Black"],"priceCents":2299,"stockQty":6}],
             "deliveryTowns":[{"townCode":"harare","feeCents":300}]}""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private OrderExpirySweeper expirySweeper;

    @Autowired
    private OrderService orderService;

    private UUID merchantId;
    private String merchantToken;
    private String customerToken;
    private UUID buyerUuid;
    private int orders;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        buyerUuid = UUID.randomUUID();
        customerToken = TestJwts.forUser(buyerUuid)
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    // ------------------------------------------------------------------
    // The listing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A published listing with options shows its axes, each option's price and stock, "
            + "the from-price, the top price and the total across options")
    void theCatalogueShowsTheOptions() throws Exception {
        Tee tee = publishTee();

        String view = mockMvc.perform(get("/marketplace/catalog/{id}", tee.listingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.hasVariants").value(true))
                // The listing price is the LOWEST option price; the range ends at XL.
                .andExpect(jsonPath("$.data.priceCents").value(1999))
                .andExpect(jsonPath("$.data.maxPriceCents").value(2299))
                // The total the browse inStock filter and the card read.
                .andExpect(jsonPath("$.data.stockQty").value(21))
                .andExpect(jsonPath("$.data.options.length()").value(2))
                .andExpect(jsonPath("$.data.options[0].name").value("Size"))
                .andExpect(jsonPath("$.data.options[1].name").value("Colour"))
                .andExpect(jsonPath("$.data.variants.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        // Values in the seller's order (M, L, XL), not alphabetical (L, M, XL).
        assertThat(JsonPath.<List<String>>read(view, "$.data.options[0].values"))
                .containsExactly("M", "L", "XL");
        assertThat(JsonPath.<List<String>>read(view, "$.data.options[1].values"))
                .containsExactly("Black");
        // Options in position order, each priced and stocked on its own.
        assertThat(JsonPath.<List<String>>read(view, "$.data.variants[*].label"))
                .containsExactly("M - Black", "L - Black", "XL - Black");
        assertThat(JsonPath.<List<String>>read(view, "$.data.variants[*].id"))
                .containsExactly(tee.m().toString(), tee.l().toString(), tee.xl().toString());
        assertThat(JsonPath.<List<Integer>>read(view, "$.data.variants[*].priceCents"))
                .containsExactly(1999, 1999, 2299);
        assertThat(JsonPath.<List<Integer>>read(view, "$.data.variants[*].stockQty"))
                .containsExactly(10, 5, 6);
        assertThat(JsonPath.<List<String>>read(view, "$.data.variants[2].values"))
                .isEqualTo(List.of("XL", "Black"));
        // Only the dearer option carries an override; the others inherit.
        assertThat(JsonPath.<List<Integer>>read(view, "$.data.variants[*].priceOverrideCents"))
                .containsExactly(2299);
        assertThat(JsonPath.<Integer>read(view, "$.data.variants[2].priceOverrideCents"))
                .isEqualTo(2299);

        // At rest: one row per option, and the listing row holds their sum.
        assertStock(tee, 10, 5, 6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_variant WHERE listing_id = ?::uuid",
                Integer.class, tee.listingId().toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT has_variants FROM listing WHERE id = ?::uuid",
                Boolean.class, tee.listingId().toString())).isTrue();
    }

    // ------------------------------------------------------------------
    // Cart -> quote -> order -> cancel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Two sizes are two cart lines priced per option; the order takes each from its own "
            + "stock and leaves the cart; a cancel puts each back exactly")
    void aCartOrderAndItsCancel() throws Exception {
        Tee tee = publishTee();

        // Somebody else has M in their cart too - the order must not touch it.
        String otherBuyer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263772345678").sign(jwtSecret);
        addToCart(otherBuyer, tee.listingId(), tee.m(), 1).andExpect(status().isOk());

        addToCart(customerToken, tee.listingId(), tee.m(), 2).andExpect(status().isOk());
        String cart = addToCart(customerToken, tee.listingId(), tee.xl(), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineCount").value(2))
                .andExpect(jsonPath("$.data.totalQuantity").value(3))
                .andExpect(jsonPath("$.data.subtotalCents").value(2 * 1999 + 2299))
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                // Newest first: XL went in after M.
                .andExpect(jsonPath("$.data.items[0].variantId").value(tee.xl().toString()))
                .andExpect(jsonPath("$.data.items[1].variantId").value(tee.m().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(cart,
                lineOf(tee.m()) + ".unitPriceCents")).containsExactly(1999);
        assertThat(JsonPath.<List<Integer>>read(cart,
                lineOf(tee.m()) + ".lineTotalCents")).containsExactly(3998);
        assertThat(JsonPath.<List<String>>read(cart,
                lineOf(tee.m()) + ".variant.label")).containsExactly("M - Black");
        assertThat(JsonPath.<List<Integer>>read(cart,
                lineOf(tee.xl()) + ".unitPriceCents")).containsExactly(2299);
        assertThat(JsonPath.<List<Integer>>read(cart,
                lineOf(tee.xl()) + ".quantity")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(cart,
                lineOf(tee.xl()) + ".variant.label")).containsExactly("XL - Black");
        // The listing block keeps saying "from" - the line's own price is unitPriceCents.
        assertThat(JsonPath.<List<Integer>>read(cart,
                lineOf(tee.xl()) + ".listing.priceCents")).containsExactly(1999);

        // A listing with options cannot be added without choosing one.
        addToCart(customerToken, tee.listingId(), null, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("variant_required"))
                .andExpect(jsonPath("$.message")
                        .value("Choose an option before adding this item to your cart"));
        assertThat(variantCartLines(buyerUuid)).isEqualTo(2);

        // Adding reserves nothing.
        assertStock(tee, 10, 5, 6);

        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                .andExpect(jsonPath("$.data.lineCount").value(2))
                .andExpect(jsonPath("$.data.subtotalCents").value(6297))
                .andExpect(jsonPath("$.data.totalCents").value(6297))
                .andExpect(jsonPath("$.data.rejections").doesNotExist());
        // A quote reserves nothing either.
        assertStock(tee, 10, 5, 6);

        String created = place("{\"fromCart\":true}");
        String orderId = JsonPath.read(created, "$.data.id");
        assertThat(JsonPath.<Integer>read(created, "$.data.totalCents")).isEqualTo(6297);
        assertThat(JsonPath.<List<String>>read(created, "$.data.items[*].variantLabel"))
                .containsExactlyInAnyOrder("M - Black", "XL - Black");

        // Each size came off its own row; L was never touched; the total follows.
        assertStock(tee, 8, 5, 5);
        // The ordered lines left this buyer's cart - and only this buyer's.
        assertThat(variantCartLines(buyerUuid)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_variant_item WHERE variant_id = ?::uuid",
                Integer.class, tee.m().toString())).isEqualTo(1);
        // The order lines snapshot the option.
        assertThat(jdbc.queryForList("SELECT variant_label FROM market_order_item "
                        + "WHERE order_id = ?::uuid AND variant_id = ?::uuid",
                String.class, orderId, tee.xl().toString())).containsExactly("XL - Black");

        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertStock(tee, 10, 5, 6);
        assertThat(stockReleased(orderId)).isTrue();

        // Cancelling again is refused and returns nothing twice.
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_order_state"));
        assertStock(tee, 10, 5, 6);
    }

    @Test
    @DisplayName("An unpaid order that lapses returns each size to its own stock exactly once, "
            + "however often the sweep runs")
    void anExpiredOrderReturnsEachSizeOnce() throws Exception {
        Tee tee = publishTee();

        String created = place(items(line(tee, tee.m(), 3), line(tee, tee.xl(), 2)));
        String orderId = JsonPath.read(created, "$.data.id");
        assertStock(tee, 7, 5, 4);

        jdbc.update("UPDATE market_order SET expires_at = now() - interval '5 minutes' "
                + "WHERE id = ?::uuid", orderId);
        expirySweeper.sweep();

        assertThat(jdbc.queryForObject("SELECT status FROM market_order WHERE id = ?::uuid",
                String.class, orderId)).isEqualTo("EXPIRED");
        assertThat(stockReleased(orderId)).isTrue();
        assertStock(tee, 10, 5, 6);

        // A second sweep and a direct re-expiry both find nothing to do.
        expirySweeper.sweep();
        assertThat(orderService.expireOne(UUID.fromString(orderId))).isFalse();
        assertStock(tee, 10, 5, 6);
    }

    // ------------------------------------------------------------------
    // Paid parcels that end without arriving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A seller's decline of a paid parcel returns each size to its own stock exactly "
            + "once - a second decline is refused and moves nothing")
    void aDeclinedParcelReturnsEachSizeOnce() throws Exception {
        Tee tee = publishTee();
        Placed order = placeAndPay(items(line(tee, tee.m(), 2), line(tee, tee.xl(), 1)));
        assertStock(tee, 8, 5, 5);

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.parcel())
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock - miscounted the M shelf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNFULFILLED"));
        assertStock(tee, 10, 5, 6);
        assertThat(stockReturned(order.parcel())).isTrue();

        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", order.parcel())
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));
        assertStock(tee, 10, 5, 6);
    }

    @Test
    @DisplayName("A buyer's cancel of a paid parcel returns each size to its own stock exactly "
            + "once - a second cancel is refused and moves nothing")
    void aBuyerCancelledParcelReturnsEachSizeOnce() throws Exception {
        Tee tee = publishTee();
        Placed order = placeAndPay(items(line(tee, tee.l(), 1), line(tee, tee.xl(), 2)));
        assertStock(tee, 10, 4, 4);

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ordered the wrong size\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfilments[0].status").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.fulfilments[0].unfulfilledBy").value("BUYER"));
        assertStock(tee, 10, 5, 6);
        assertThat(stockReturned(order.parcel())).isTrue();

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.id(), order.parcel())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_not_cancellable"));
        assertStock(tee, 10, 5, 6);
    }

    // ------------------------------------------------------------------
    // The label on every surface
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The option label rides the buyer's order, the seller's card, the courier run, "
            + "the earnings row and the statement - and a line without options reads exactly "
            + "as before")
    void theLabelRidesEverySurface() throws Exception {
        Tee tee = publishTee();
        String lantern = publishPlainLantern();
        String address = saveAddress();
        Placed order = placeAndPay("""
                {"items":[%s,%s,{"listingId":"%s","quantity":1}],
                 "deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                .formatted(line(tee, tee.xl(), 1), line(tee, tee.m(), 2), lantern, address));
        assertStock(tee, 8, 5, 5);

        // The buyer's order: the option's own price, the label, and a plain line
        // with no option keys at all.
        String view = order(order.id())
                .andExpect(jsonPath("$.data.subtotalCents").value(2299 + 2 * 1999 + 1550))
                // One seller ships one parcel: the dearest line's fee, once.
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(300))
                .andReturn().getResponse().getContentAsString();
        assertLines(view, "$.data.items", tee, lantern);
        assertLines(view, "$.data.fulfilments[0].items", tee, lantern);

        // The seller's card.
        String card = mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andReturn().getResponse().getContentAsString();
        assertLines(card, "$.data.items[0].items", tee, lantern);

        // The courier's run, once dispatched.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.parcel())
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        String driverToken = TestJwts.forUser(UUID.randomUUID())
                .organization(merchantId, "STAFF", List.of("marketplace")).sign(jwtSecret);
        String run = mockMvc.perform(get("/marketplace/deliveries")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(run,
                "$.data[0].items[?(@.variantLabel == 'XL - Black')].title"))
                .containsExactly("Cotton Crew Tee");
        assertThat(JsonPath.<List<Integer>>read(run,
                "$.data[0].items[?(@.variantLabel == 'XL - Black')].quantity"))
                .containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(run,
                "$.data[0].items[?(@.variantLabel == 'M - Black')].quantity"))
                .containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(run,
                "$.data[0].items[?(@.title == 'Solar Lantern 20W')].quantity")).containsExactly(1);
        assertThat(JsonPath.<List<Object>>read(run,
                "$.data[0].items[?(@.title == 'Solar Lantern 20W')].variantLabel")).isEmpty();

        // The earnings row: held at the options' own prices, and the summary
        // names each option.
        String rows = mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].grossCents").value(2299 + 2 * 1999 + 1550 + 300))
                .andExpect(jsonPath("$.data.items[0].deliveryFeeCents").value(300))
                .andReturn().getResponse().getContentAsString();
        String summary = JsonPath.read(rows, "$.data.items[0].itemSummary");
        assertThat(Arrays.asList(summary.split(", "))).containsExactlyInAnyOrder(
                "1 x Cotton Crew Tee (XL - Black)",
                "2 x Cotton Crew Tee (M - Black)",
                "1 x Solar Lantern 20W");

        // The statement carries the same summary in its items cell.
        String csv = mockMvc.perform(get("/marketplace/settlements/statement")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> csvLines = csv.lines().toList();
        assertThat(csvLines).hasSize(2);
        assertThat(csvLines.get(1))
                .contains(JsonPath.<String>read(view, "$.data.orderRef"))
                .contains("1 x Cotton Crew Tee (XL - Black)")
                .contains("2 x Cotton Crew Tee (M - Black)")
                .contains("1 x Solar Lantern 20W");
    }

    // ------------------------------------------------------------------
    // The seller's quick restock of one size
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Setting one size's stock moves that size only - the reservation on another size "
            + "is untouched - and the listing total follows")
    void theQuickRestockSetsOneSize() throws Exception {
        Tee tee = publishTee();
        // A pending order holds 2 x M.
        String orderId = JsonPath.read(place(items(line(tee, tee.m(), 2))), "$.data.id");
        assertStock(tee, 8, 5, 6);

        String updated = restock(tee, tee.l(), 12, merchantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(26))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(updated, "$.data.variants[*].stockQty"))
                .containsExactly(8, 12, 6);
        assertStock(tee, 8, 12, 6);

        // The held M units still come back to M, on top of the restock.
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        assertStock(tee, 10, 12, 6);

        // Setting a size to zero is a set, not a delta.
        restock(tee, tee.xl(), 0, merchantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(22));
        assertStock(tee, 10, 12, 0);

        // An id that is not an option of this listing moves nothing.
        restock(tee, UUID.randomUUID(), 3, merchantToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("variant_not_found"));
        assertStock(tee, 10, 12, 0);
    }

    @Test
    @DisplayName("The quick restock is a seller action: anonymous is 401, a customer is 403, "
            + "another seller is 403 - and none of them moves a unit")
    void theQuickRestockIsASellerAction() throws Exception {
        Tee tee = publishTee();

        mockMvc.perform(patch("/marketplace/listings/{id}/variants/{vid}/stock",
                        tee.listingId(), tee.m())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQty\":99}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        restock(tee, tee.m(), 99, customerToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String otherSeller = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        restock(tee, tee.m(), 99, otherSeller)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("listing_not_owned"));

        assertStock(tee, 10, 5, 6);
    }

    // ------------------------------------------------------------------

    /** The listing and its three options, M / L / XL, all Black. */
    private record Tee(UUID listingId, UUID m, UUID l, UUID xl) {
    }

    private record Placed(String id, String parcel) {
    }

    /** Exact per-size stock off the variant rows, and the listing total as their sum. */
    private void assertStock(Tee tee, int m, int l, int xl) {
        assertThat(variantStock(tee.m())).as("M stock").isEqualTo(m);
        assertThat(variantStock(tee.l())).as("L stock").isEqualTo(l);
        assertThat(variantStock(tee.xl())).as("XL stock").isEqualTo(xl);
        assertThat(jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, tee.listingId().toString())).as("listing total").isEqualTo(m + l + xl);
    }

    private int variantStock(UUID variantId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing_variant WHERE id = ?::uuid",
                Integer.class, variantId.toString());
    }

    private int variantCartLines(UUID buyer) {
        return jdbc.queryForObject("SELECT count(*) FROM cart_variant_item WHERE buyer_uuid = ?::uuid",
                Integer.class, buyer.toString());
    }

    private boolean stockReleased(String orderId) {
        return jdbc.queryForObject("SELECT stock_released FROM market_order WHERE id = ?::uuid",
                Boolean.class, orderId);
    }

    private boolean stockReturned(String parcelId) {
        return jdbc.queryForObject("SELECT stock_returned FROM order_fulfilment WHERE id = ?::uuid",
                Boolean.class, parcelId);
    }

    /** The XL line is priced and labelled as its option, the M line likewise,
     *  and the plain lantern line carries no option keys at all. */
    private static void assertLines(String json, String path, Tee tee, String lantern) {
        assertThat(JsonPath.<List<Object>>read(json, path)).hasSize(3);
        String xl = path + "[?(@.variantId == '" + tee.xl() + "')]";
        assertThat(JsonPath.<List<String>>read(json, xl + ".variantLabel")).containsExactly("XL - Black");
        assertThat(JsonPath.<List<String>>read(json, xl + ".titleSnapshot"))
                .containsExactly("Cotton Crew Tee");
        assertThat(JsonPath.<List<Integer>>read(json, xl + ".unitPriceCents")).containsExactly(2299);
        assertThat(JsonPath.<List<Integer>>read(json, xl + ".lineTotalCents")).containsExactly(2299);
        String m = path + "[?(@.variantId == '" + tee.m() + "')]";
        assertThat(JsonPath.<List<String>>read(json, m + ".variantLabel")).containsExactly("M - Black");
        assertThat(JsonPath.<List<Integer>>read(json, m + ".unitPriceCents")).containsExactly(1999);
        assertThat(JsonPath.<List<Integer>>read(json, m + ".lineTotalCents")).containsExactly(3998);
        String plain = path + "[?(@.listingId == '" + lantern + "')]";
        assertThat(JsonPath.<List<String>>read(json, plain + ".titleSnapshot"))
                .containsExactly("Solar Lantern 20W");
        assertThat(JsonPath.<List<Object>>read(json, plain + ".variantId")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(json, plain + ".variantLabel")).isEmpty();
    }

    private static String lineOf(UUID variantId) {
        return "$.data.items[?(@.variantId == '" + variantId + "')]";
    }

    private static String line(Tee tee, UUID variantId, int quantity) {
        return """
                {"listingId":"%s","variantId":"%s","quantity":%d}"""
                .formatted(tee.listingId(), variantId, quantity);
    }

    private static String items(String... lines) {
        return "{\"items\":[" + String.join(",", lines) + "],\"deliveryMethod\":\"COLLECTION\"}";
    }

    private ResultActions addToCart(String token, UUID listingId, UUID variantId, int quantity)
            throws Exception {
        String body = variantId == null
                ? "{\"listingId\":\"%s\",\"quantity\":%d}".formatted(listingId, quantity)
                : "{\"listingId\":\"%s\",\"variantId\":\"%s\",\"quantity\":%d}"
                        .formatted(listingId, variantId, quantity);
        return mockMvc.perform(post("/marketplace/cart/items")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions restock(Tee tee, UUID variantId, int quantity, String token)
            throws Exception {
        return mockMvc.perform(patch("/marketplace/listings/{id}/variants/{vid}/stock",
                tee.listingId(), variantId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stockQty\":" + quantity + "}"));
    }

    private ResultActions order(String orderId) throws Exception {
        return mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
    }

    private String place(String body) throws Exception {
        return mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "variant-flow-" + (++orders))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Placed placeAndPay(String body) throws Exception {
        String created = place(body);
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        Number total = JsonPath.read(created, "$.data.totalCents");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-VAR-" + orders + "\",\"amountCents\":"
                                + total.longValue() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        String parcel = JsonPath.read(order(orderId).andReturn().getResponse().getContentAsString(),
                "$.data.fulfilments[0].id");
        return new Placed(orderId, parcel);
    }

    private String saveAddress() throws Exception {
        String saved = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","recipientName":"Tariro Moyo",
                                 "recipientMsisdn":"0771234567","line1":"14 Samora Machel Ave",
                                 "townCode":"harare"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(saved, "$.data.id");
    }

    /** Creates the tee over HTTP, gives it a primary image and publishes it. */
    private Tee publishTee() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TEE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.hasVariants").value(true))
                .andExpect(jsonPath("$.data.stockQty").value(21))
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        publish(listingId);
        return new Tee(UUID.fromString(listingId),
                variantId(created, "M - Black"),
                variantId(created, "L - Black"),
                variantId(created, "XL - Black"));
    }

    private String publishPlainLantern() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10,
                                 "deliveryTowns":[{"townCode":"harare","feeCents":300}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.hasVariants").value(false))
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        publish(listingId);
        return listingId;
    }

    private void publish(String listingId) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    private static UUID variantId(String listingJson, String label) {
        List<String> ids = JsonPath.read(listingJson,
                "$.data.variants[?(@.label == '" + label + "')].id");
        assertThat(ids).as("variant " + label).hasSize(1);
        return UUID.fromString(ids.getFirst());
    }
}
