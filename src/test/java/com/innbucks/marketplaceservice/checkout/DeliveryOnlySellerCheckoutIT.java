package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.order.MarketOrderRepository;
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
 * A delivery-only seller (V20) at checkout, over real HTTP and a real Postgres:
 * the pricer's two new reads ({@code findByListingIdIn} for every on-sale
 * listing, {@code findCollectionDisabledAmong} for the on-sale sellers) against
 * the V20 schema, the refusal before any stock moves, and the per-seller method
 * written with the order and copied onto each parcel when it is paid.
 *
 * <p>The seller is made delivery-only by a direct UPDATE of
 * {@code marketplace_seller.collection_enabled} - this class pins checkout's
 * reading of the flag, not the endpoint that sets it.
 */
class DeliveryOnlySellerCheckoutIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    private static final String ADDRESS_BODY = """
            {
              "label": "Home",
              "recipientName": "Tariro Moyo",
              "recipientMsisdn": "0771234567",
              "line1": "14 Samora Machel Ave",
              "townCode": "harare"
            }""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private MarketOrderRepository orderRepository;

    private UUID collectsId;
    private UUID deliversOnlyId;
    private String collectsToken;
    private String deliversOnlyToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        collectsId = UUID.randomUUID();
        deliversOnlyId = UUID.randomUUID();
        collectsToken = TestJwts.merchantAdmin(UUID.randomUUID(), collectsId, jwtSecret);
        deliversOnlyToken = TestJwts.merchantAdmin(UUID.randomUUID(), deliversOnlyId, jwtSecret);
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    @Test
    @DisplayName("quote 200 names the delivery-only line; a COLLECTION order is refused reserving nothing; a DELIVERY order goes through and each parcel is stamped")
    void aDeliveryOnlySellerAtCheckout() throws Exception {
        String lantern = publish(collectsToken, "Solar Lantern 20W", 1550, 200);
        String earbuds = publish(deliversOnlyToken, "Wireless Earbuds", 2599, 500);
        // The first listing created the seller's record; turn collection off.
        assertThat(jdbc.update("UPDATE marketplace_seller SET collection_enabled = FALSE "
                + "WHERE merchant_id = ?", deliversOnlyId)).isEqualTo(1);

        String addressId = JsonPath.read(mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.id");

        addToCart(lantern);
        addToCart(earbuds);

        // --- The cart asks no delivery question: nothing changed there -------
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].issue").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].issue").doesNotExist());

        // --- A COLLECTION quote: a 200 naming the line and the seller ----------
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"COLLECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.rejections", hasSize(1)))
                .andExpect(jsonPath("$.data.rejections[0].listingId").value(earbuds))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("COLLECTION_NOT_OFFERED"))
                .andExpect(jsonPath("$.data.rejections[0].message")
                        .value("Wireless Earbuds is delivery only"))
                .andExpect(jsonPath("$.data.rejections[0].unitPriceCents").value(2599))
                .andExpect(jsonPath("$.data.rejections[0].merchantId").value(deliversOnlyId.toString()))
                .andExpect(jsonPath("$.data.subtotalCents").value(1550))
                // Judged against the buyer's default address, which is in Harare.
                .andExpect(jsonPath("$.data.availabilityTownCode").value("harare"))
                .andExpect(jsonPath("$.data.deliveryAddress").doesNotExist())
                // The cart's order: newest first, so the earbuds' seller leads.
                .andExpect(jsonPath("$.data.sellers", hasSize(2)))
                .andExpect(jsonPath("$.data.sellers[?(@.merchantId == '" + deliversOnlyId
                        + "')].availableMethods[*]", contains("DELIVERY")))
                .andExpect(jsonPath("$.data.sellers[?(@.merchantId == '" + collectsId
                        + "')].availableMethods[*]", contains("DELIVERY", "COLLECTION")))
                // Only the seller who collects is on the collection list: the
                // delivery-only one is never "arrange collection with the seller".
                .andExpect(jsonPath("$.data.collectionPoints", hasSize(1)))
                .andExpect(jsonPath("$.data.collectionPoints[0].merchantId").value(collectsId.toString()));

        // --- A DELIVERY quote for the same basket is ready ----------------------
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"DELIVERY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(700))
                .andExpect(jsonPath("$.data.sellers[?(@.merchantId == '" + deliversOnlyId
                        + "')].deliveryFeeCents", contains(500)));

        // --- A COLLECTION order is refused before anything moves ---------------
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "delivery-only-collect-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"COLLECTION\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("collection_not_offered"))
                .andExpect(jsonPath("$.message")
                        .value("Wireless Earbuds is delivery only. Choose delivery or remove it."))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("COLLECTION_NOT_OFFERED"))
                .andExpect(jsonPath("$.data.rejections[0].merchantId").value(deliversOnlyId.toString()));
        assertStock(lantern, 10);
        assertStock(earbuds, 10);
        assertThat(orderRepository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order_seller", Long.class)).isZero();
        // The refused order left the cart as it was.
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.items", hasSize(2)));

        // --- A DELIVERY order goes through ------------------------------------
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "delivery-only-deliver-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                                .formatted(addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveryMethod").value("DELIVERY"))
                .andExpect(jsonPath("$.data.subtotalCents").value(4149))
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(700))
                .andExpect(jsonPath("$.data.totalCents").value(4849))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        assertStock(lantern, 9);
        assertStock(earbuds, 9);
        // One row per seller, in the order's own transaction.
        assertThat(sellerMethods(orderId)).isEqualTo(Map.of(
                collectsId, "DELIVERY", deliversOnlyId, "DELIVERY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order_delivery_fee "
                + "WHERE order_id = ?", Long.class, orderId)).isEqualTo(2L);

        // --- Paid: each parcel copies its seller's method ---------------------
        confirm(orderRef, 4849);
        assertThat(parcelMethods(orderId)).isEqualTo(Map.of(
                collectsId, "DELIVERY", deliversOnlyId, "DELIVERY"));
    }

    @Test
    @DisplayName("A COLLECTION order from a seller who collects writes COLLECTION for that seller and stamps its parcel the same")
    void aCollectionOrderStampsCollection() throws Exception {
        String lantern = publish(collectsToken, "Solar Lantern 20W", 1550, 200);
        // Another seller being delivery-only changes nothing for this one.
        publish(deliversOnlyToken, "Wireless Earbuds", 2599, 500);
        jdbc.update("UPDATE marketplace_seller SET collection_enabled = FALSE WHERE merchant_id = ?",
                deliversOnlyId);

        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "collects-collect-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":2}],"deliveryMethod":"COLLECTION"}"""
                                .formatted(lantern)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(created, "$.data.id"));

        assertThat(sellerMethods(orderId)).isEqualTo(Map.of(collectsId, "COLLECTION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order_delivery_fee "
                + "WHERE order_id = ?", Long.class, orderId)).isZero();

        confirm(JsonPath.read(created, "$.data.orderRef"), 3100);
        assertThat(parcelMethods(orderId)).isEqualTo(Map.of(collectsId, "COLLECTION"));
        assertThat(jdbc.queryForObject("SELECT delivery_fee_cents FROM order_fulfilment "
                + "WHERE order_id = ?", Long.class, orderId)).isZero();
    }

    // ------------------------------------------------------------------

    /** Creates a listing delivered to Harare for {@code feeCents}, and publishes it. */
    private String publish(String merchantToken, String title, long priceCents, long feeCents)
            throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "description": "V20 checkout",
                                  "categoryCode": "electronics",
                                  "priceCents": %d,
                                  "stockQty": 10,
                                  "deliveryTowns": [{ "townCode": "harare", "feeCents": %d }]
                                }""".formatted(title, priceCents, feeCents)))
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

    private void addToCart(String listingId) throws Exception {
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId)))
                .andExpect(status().isOk());
    }

    private void assertStock(String listingId, int expected) throws Exception {
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(expected));
    }

    private void confirm(String orderRef, long amountCents) throws Exception {
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":%d}"
                                .formatted(orderRef, amountCents)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    private Map<UUID, String> sellerMethods(UUID orderId) {
        return methods("SELECT merchant_id, delivery_method FROM market_order_seller WHERE order_id = ?",
                orderId);
    }

    private Map<UUID, String> parcelMethods(UUID orderId) {
        return methods("SELECT merchant_id, delivery_method FROM order_fulfilment WHERE order_id = ?",
                orderId);
    }

    private Map<UUID, String> methods(String sql, UUID orderId) {
        List<Map.Entry<UUID, String>> rows = jdbc.query(sql, (rs, n) -> Map.entry(
                rs.getObject("merchant_id", UUID.class), rs.getString("delivery_method")), orderId);
        return Map.ofEntries(rows.toArray(Map.Entry[]::new));
    }
}
