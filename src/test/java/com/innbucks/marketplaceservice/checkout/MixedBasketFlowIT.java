package com.innbucks.marketplaceservice.checkout;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ONE basket, two sellers who can each only reach the buyer one way (V20, the
 * per-seller API), end to end over real Postgres and the real security chain
 * with {@code marketplace.delivery.per-seller-methods-enabled} ON:
 *
 * <ul>
 *   <li>seller A only delivers ({@code collection_enabled = false}) and
 *       delivers to Harare for 8.00;</li>
 *   <li>seller B delivers to Bulawayo only - so for a Harare buyer, collection
 *       at B's counter is the only way.</li>
 * </ul>
 *
 * <p>No single method works for the basket, which is the whole point: the
 * uniform quotes say so, the per-seller quote is ready, and the order, the
 * payment, each parcel, the courier, the collection code and the escrow then
 * each follow THAT seller's method.
 *
 * <p>Its own class because the switch is a property (shared with
 * {@code CollectionToggleFlowIT}'s context); the default context runs it OFF,
 * which {@link MixedBasketDefaultOffIT} pins.
 */
// The SAME property set as CollectionToggleFlowIT, in the same order: one shared
// context rather than another connection pool against the test Postgres.
// Delivery-only sellers being switched on changes nothing here - seller A is
// made delivery-only by a direct UPDATE.
@TestPropertySource(properties = {
        "marketplace.delivery.delivery-only-sellers-enabled=true",
        "marketplace.delivery.per-seller-methods-enabled=true"
})
class MixedBasketFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID sellerA;
    private UUID sellerB;
    private String tokenA;
    private String tokenB;
    private String driverA;
    private String customer;

    @BeforeEach
    void mintTokens() {
        sellerA = UUID.randomUUID();
        sellerB = UUID.randomUUID();
        tokenA = TestJwts.merchantAdmin(UUID.randomUUID(), sellerA, jwtSecret);
        tokenB = TestJwts.merchantAdmin(UUID.randomUUID(), sellerB, jwtSecret);
        driverA = TestJwts.forUser(UUID.randomUUID())
                .organization(sellerA, "STAFF", List.of("marketplace")).sign(jwtSecret);
        customer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    @Test
    @DisplayName("Delivered from A, collected from B: quote, order, replay, payment, parcels, courier, code and escrow each follow the seller's own method")
    void aBasketDeliveredFromOneSellerAndCollectedFromAnother() throws Exception {
        String earbuds = publish(tokenA, "Wireless Earbuds", 2599, "harare", 800);
        String hose = publish(tokenB, "Garden Hose", 1550, "bulawayo", 300);
        // A only delivers. The first listing registered the seller.
        assertThat(jdbc.update("UPDATE marketplace_seller SET collection_enabled = FALSE "
                + "WHERE merchant_id = ?", sellerA)).isEqualTo(1);
        String counter = JsonPath.read(mockMvc.perform(post("/marketplace/sellers/me/collection-points")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Avondale shop","townCode":"harare","line1":"14 Samora Machel Ave"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
        String address = saveAddress();

        mockMvc.perform(get("/marketplace/checkout/options")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.perSellerDeliveryMethods").value(true));

        String items = """
                "items":[{"listingId":"%s","quantity":1},{"listingId":"%s","quantity":1}]"""
                .formatted(earbuds, hose);

        // --- No ONE method works for this basket --------------------------------
        quote("{" + items + ",\"deliveryMethod\":\"DELIVERY\",\"deliveryAddressId\":\"" + address + "\"}")
                .andExpect(jsonPath("$.data.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.rejections", hasSize(1)))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("NOT_DELIVERED_TO_TOWN"))
                .andExpect(jsonPath("$.data.rejections[0].merchantId").value(sellerB.toString()));
        quote("{" + items + ",\"deliveryMethod\":\"COLLECTION\",\"deliveryAddressId\":\"" + address + "\"}")
                .andExpect(jsonPath("$.data.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.rejections", hasSize(1)))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("COLLECTION_NOT_OFFERED"))
                .andExpect(jsonPath("$.data.rejections[0].merchantId").value(sellerA.toString()))
                // ...and the quote said what each seller CAN do, before the shopper picks.
                .andExpect(jsonPath("$.data.sellers[0].availableMethods", contains("DELIVERY")))
                .andExpect(jsonPath("$.data.sellers[1].availableMethods", contains("COLLECTION")));

        // --- One method per seller: ready -------------------------------------
        String body = "{" + items + ",\"deliveryAddressId\":\"" + address + "\","
                + "\"sellerDeliveryMethods\":[{\"merchantId\":\"" + sellerA + "\",\"deliveryMethod\":\"DELIVERY\"},"
                + "{\"merchantId\":\"" + sellerB + "\",\"deliveryMethod\":\"COLLECTION\"}]}";
        quote(body)
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                .andExpect(jsonPath("$.data.rejections").doesNotExist())
                .andExpect(jsonPath("$.data.deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.deliveryAddress.id").value(address))
                .andExpect(jsonPath("$.data.subtotalCents").value(4149))
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(800))
                .andExpect(jsonPath("$.data.totalCents").value(4949))
                .andExpect(jsonPath("$.data.deliveryFees", hasSize(1)))
                .andExpect(jsonPath("$.data.deliveryFees[0].merchantId").value(sellerA.toString()))
                .andExpect(jsonPath("$.data.deliveryFees[0].feeCents").value(800))
                .andExpect(jsonPath("$.data.sellers[0].merchantId").value(sellerA.toString()))
                .andExpect(jsonPath("$.data.sellers[0].deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.sellers[0].deliveryFeeCents").value(800))
                .andExpect(jsonPath("$.data.sellers[1].merchantId").value(sellerB.toString()))
                .andExpect(jsonPath("$.data.sellers[1].deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.sellers[1].deliveryFeeCents").doesNotExist())
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(1)))
                .andExpect(jsonPath("$.data.collectionPoints[0].merchantId").value(sellerB.toString()))
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.id").value(counter));
        // The quote held nothing.
        assertStock(earbuds, 10);
        assertStock(hose, 10);

        // --- The order: the body just quoted --------------------------------------
        String created = createOrder(body, "mixed-basket-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.deliveryAddress.line1").value("14 Samora Machel Ave"))
                .andExpect(jsonPath("$.data.deliveryAddress.addressId").value(address))
                .andExpect(jsonPath("$.data.subtotalCents").value(4149))
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(800))
                .andExpect(jsonPath("$.data.totalCents").value(4949))
                .andExpect(jsonPath("$.data.sellers", hasSize(2)))
                .andExpect(jsonPath("$.data.sellers[0].merchantId").value(sellerA.toString()))
                .andExpect(jsonPath("$.data.sellers[0].deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.sellers[0].deliveryFeeCents").value(800))
                .andExpect(jsonPath("$.data.sellers[1].merchantId").value(sellerB.toString()))
                .andExpect(jsonPath("$.data.sellers[1].deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.sellers[1].deliveryFeeCents").doesNotExist())
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(1)))
                .andExpect(jsonPath("$.data.collectionPoints[0].merchantId").value(sellerB.toString()))
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.id").value(counter))
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        assertStock(earbuds, 9);
        assertStock(hose, 9);

        // At rest: the summary, the ONE destination, a row per seller with ITS
        // method, a fee row for the deliverer only, a point for the collector only.
        Map<String, Object> order = jdbc.queryForMap("""
                SELECT delivery_method, delivery_town_code, delivery_address_id::text AS address,
                       delivery_fee_cents FROM market_order WHERE id = ?::uuid""", orderId);
        assertThat(order).containsEntry("delivery_method", "DELIVERY")
                .containsEntry("delivery_town_code", "harare")
                .containsEntry("address", address)
                .containsEntry("delivery_fee_cents", 800L);
        assertThat(methods("SELECT merchant_id, delivery_method FROM market_order_seller "
                + "WHERE order_id = ?::uuid", orderId))
                .isEqualTo(Map.of(sellerA, "DELIVERY", sellerB, "COLLECTION"));
        assertThat(jdbc.queryForList("SELECT merchant_id FROM market_order_delivery_fee "
                + "WHERE order_id = ?::uuid", UUID.class, orderId)).containsExactly(sellerA);
        assertThat(jdbc.queryForObject("SELECT fee_cents FROM market_order_delivery_fee "
                + "WHERE order_id = ?::uuid", Long.class, orderId)).isEqualTo(800L);
        assertThat(jdbc.queryForList("SELECT merchant_id FROM market_order_collection_point "
                + "WHERE order_id = ?::uuid", UUID.class, orderId)).containsExactly(sellerB);

        // --- The same key replays the SAME bytes, holding nothing more ---------
        String replayed = createOrder(body, "mixed-basket-1")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayed).isEqualTo(created);
        assertStock(earbuds, 9);
        assertStock(hose, 9);

        // --- Read back: the ids the quote named, the order records -------------
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellers[0].merchantId").value(sellerA.toString()))
                .andExpect(jsonPath("$.data.sellers[0].deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.sellers[0].deliveryFeeCents").value(800))
                .andExpect(jsonPath("$.data.sellers[1].deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.collectionPoints[0].collectionPoint.id").value(counter));

        // --- Paid: each parcel copies its seller's method and fee -------------
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-MIXED-BASKET-1\",\"amountCents\":4949}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        assertThat(methods("SELECT merchant_id, delivery_method FROM order_fulfilment "
                + "WHERE order_id = ?::uuid", orderId))
                .isEqualTo(Map.of(sellerA, "DELIVERY", sellerB, "COLLECTION"));
        String parcelA = parcelOf(orderId, sellerA);
        String parcelB = parcelOf(orderId, sellerB);
        assertThat(jdbc.queryForObject("SELECT delivery_fee_cents FROM order_fulfilment WHERE id = ?::uuid",
                Long.class, parcelA)).isEqualTo(800L);
        assertThat(jdbc.queryForObject("SELECT delivery_fee_cents FROM order_fulfilment WHERE id = ?::uuid",
                Long.class, parcelB)).isZero();

        // --- Escrow: A is owed goods + the trip, B the goods ------------------
        assertThat(jdbc.queryForObject("SELECT gross_cents FROM merchant_settlement "
                + "WHERE fulfilment_id = ?::uuid", Long.class, parcelA)).isEqualTo(2599L + 800L);
        assertThat(jdbc.queryForObject("SELECT gross_cents FROM merchant_settlement "
                + "WHERE fulfilment_id = ?::uuid", Long.class, parcelB)).isEqualTo(1550L);

        // --- A's parcel is a courier delivery -------------------------------------
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", parcelA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/deliveries").header("Authorization", "Bearer " + driverA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fulfilmentId").value(parcelA))
                .andExpect(jsonPath("$.data[0].destination.line1").value("14 Samora Machel Ave"));
        mockMvc.perform(post("/marketplace/deliveries/{id}/location", parcelA)
                        .header("Authorization", "Bearer " + driverA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":-17.8292,\"longitude\":31.0539,\"accuracyMeters\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", parcelA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value("SELLER_MARKED"));

        // --- B's parcel is a collection: a seller's word cannot close it ------
        mockMvc.perform(post("/marketplace/fulfilments/{id}/delivered", parcelB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("collect_code_required"));
        String minted = mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/collect-code",
                                orderId, parcelB)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", parcelB)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(JsonPath.<String>read(minted, "$.data.code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value("COLLECTION_CODE"));

        // Everything arrived, each its own way.
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.fulfilments[?(@.id == '%s')].deliveryMethod", parcelA)
                        .value("DELIVERY"))
                .andExpect(jsonPath("$.data.fulfilments[?(@.id == '%s')].deliveryMethod", parcelB)
                        .value("COLLECTION"));
    }

    @Test
    @DisplayName("A per-seller order still refuses a bad choice before anything is held: a seller named twice is 400, a line the chosen method cannot reach is 422")
    void badPerSellerOrdersHoldNothing() throws Exception {
        String earbuds = publish(tokenA, "Wireless Earbuds", 2599, "harare", 800);
        String hose = publish(tokenB, "Garden Hose", 1550, "bulawayo", 300);
        String address = saveAddress();
        String items = """
                "items":[{"listingId":"%s","quantity":1},{"listingId":"%s","quantity":1}]"""
                .formatted(earbuds, hose);

        createOrder("{" + items + ",\"deliveryAddressId\":\"" + address + "\","
                + "\"sellerDeliveryMethods\":[{\"merchantId\":\"" + sellerA + "\",\"deliveryMethod\":\"DELIVERY\"},"
                + "{\"merchantId\":\"" + sellerA + "\",\"deliveryMethod\":\"COLLECTION\"}]}", "mixed-bad-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("duplicate_delivery_method_choice"))
                .andExpect(jsonPath("$.message")
                        .value("sellerDeliveryMethods names the same seller more than once"));

        // B chosen for DELIVERY, which does not reach Harare.
        createOrder("{" + items + ",\"deliveryAddressId\":\"" + address + "\","
                + "\"sellerDeliveryMethods\":[{\"merchantId\":\"" + sellerB + "\",\"deliveryMethod\":\"DELIVERY\"}]}",
                "mixed-bad-2")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("not_delivered_to_town"))
                .andExpect(jsonPath("$.data.rejections[0].merchantId").value(sellerB.toString()));

        // A null field inside an entry is Bean Validation's 400.
        createOrder("{" + items + ",\"sellerDeliveryMethods\":[{\"merchantId\":\"" + sellerA + "\"}]}",
                "mixed-bad-3")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertStock(earbuds, 10);
        assertStock(hose, 10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order_seller", Long.class)).isZero();
    }

    // ------------------------------------------------------------------

    private ResultActions quote(String body) throws Exception {
        return mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private ResultActions createOrder(String body, String key) throws Exception {
        return mockMvc.perform(post("/marketplace/orders")
                .header("Authorization", "Bearer " + customer)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** A Harare address in the buyer's book. */
    private String saveAddress() throws Exception {
        String saved = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","recipientName":"Tariro Moyo",
                                 "recipientMsisdn":"0771234567","line1":"14 Samora Machel Ave",
                                 "townCode":"harare"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(saved, "$.data.id");
    }

    /** Creates a listing delivered to ONE town for {@code feeCents}, and publishes it. */
    private String publish(String token, String title, long priceCents, String town, long feeCents)
            throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","categoryCode":"electronics",
                                 "priceCents":%d,"stockQty":10,
                                 "deliveryTowns":[{"townCode":"%s","feeCents":%d}]}"""
                                .formatted(title, priceCents, town, feeCents)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return listingId;
    }

    private void assertStock(String listingId, int expected) throws Exception {
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(expected));
    }

    private String parcelOf(String orderId, UUID merchantId) {
        return jdbc.queryForObject(
                "SELECT id::text FROM order_fulfilment WHERE order_id = ?::uuid AND merchant_id = ?",
                String.class, orderId, merchantId);
    }

    private Map<UUID, String> methods(String sql, String orderId) {
        List<Map.Entry<UUID, String>> rows = jdbc.query(sql, (rs, n) -> Map.entry(
                rs.getObject("merchant_id", UUID.class), rs.getString("delivery_method")), orderId);
        return Map.ofEntries(rows.toArray(Map.Entry[]::new));
    }
}
