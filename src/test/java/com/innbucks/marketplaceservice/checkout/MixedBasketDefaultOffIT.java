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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DEFAULT posture of the per-seller switch (V20): no
 * {@code @TestPropertySource}, so this runs on the shipped configuration, where
 * {@code marketplace.delivery.per-seller-methods-enabled} is OFF. The cell says
 * so, a quote or order naming a method per seller is refused before anything
 * is held, and a body without the field behaves exactly as it always did.
 *
 * <p>Separate from {@link MixedBasketFlowIT}, which proves the switched-on half:
 * one class flipping the property mid-run is how a gate stops being tested.
 */
class MixedBasketDefaultOffIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    private UUID sellerA;
    private UUID sellerB;
    private String tokenA;
    private String tokenB;
    private String customer;

    @BeforeEach
    void mintTokens() {
        sellerA = UUID.randomUUID();
        sellerB = UUID.randomUUID();
        tokenA = TestJwts.merchantAdmin(UUID.randomUUID(), sellerA, jwtSecret);
        tokenB = TestJwts.merchantAdmin(UUID.randomUUID(), sellerB, jwtSecret);
        customer = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    @Test
    @DisplayName("Switch OFF: options says false, a per-seller quote and order are 422 seller_delivery_methods_disabled, nothing is held, and a plain body still orders")
    void perSellerMethodsAreOffByDefault() throws Exception {
        String earbuds = publish(tokenA, "Wireless Earbuds", 2599);
        String hose = publish(tokenB, "Garden Hose", 1550);
        String items = """
                "items":[{"listingId":"%s","quantity":1},{"listingId":"%s","quantity":1}]"""
                .formatted(earbuds, hose);
        String perSeller = "{" + items + ",\"sellerDeliveryMethods\":[{\"merchantId\":\""
                + sellerA + "\",\"deliveryMethod\":\"DELIVERY\"}]}";

        mockMvc.perform(get("/marketplace/checkout/options")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.perSellerDeliveryMethods").value(false));

        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perSeller))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("seller_delivery_methods_disabled"))
                .andExpect(jsonPath("$.message").value("Choosing delivery or collection per seller "
                        + "is not available yet - choose one method for the whole order"));

        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", "per-seller-off-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(perSeller))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("seller_delivery_methods_disabled"));
        assertStock(earbuds, 10);
        assertStock(hose, 10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order_seller", Long.class)).isZero();

        // An empty list names nobody: the body is the plain one, and orders.
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", "per-seller-off-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + items + ",\"sellerDeliveryMethods\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.sellers[0].merchantId").value(sellerA.toString()))
                .andExpect(jsonPath("$.data.sellers[0].deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.sellers[1].merchantId").value(sellerB.toString()))
                .andExpect(jsonPath("$.data.sellers[1].deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.collectionPoints.length()").value(2));
        assertStock(earbuds, 9);
        assertStock(hose, 9);
    }

    private String publish(String token, String title, long priceCents) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","categoryCode":"electronics",
                                 "priceCents":%d,"stockQty":10,
                                 "deliveryTowns":[{"townCode":"harare","feeCents":500}]}"""
                                .formatted(title, priceCents)))
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
}
