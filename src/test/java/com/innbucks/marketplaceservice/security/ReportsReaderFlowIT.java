package com.innbucks.marketplaceservice.security;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code marketplace-reports:read} end to end, over the real security chain and
 * a real Postgres, with the token shape the developer account carries on
 * production: roles {@code [PRODUCT_OFFICER]}, the permission in {@code perms},
 * no organization claims. It reads another seller's data on the granted GETs,
 * is refused every write, never sees a payout destination or a buyer's
 * contact in full, and the same token without the permission reads nothing.
 */
class ReportsReaderFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};
    private static final String BUYER_PHONE = "+263771234567";
    private static final String GIFT_PHONE = "+263772223344";
    private static final String ACCOUNT_NUMBER = "62003451987";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private UUID merchantId;
    private UUID buyerUuid;
    private String merchantToken;
    private String customerToken;
    private String superAdminToken;
    private String readerToken;
    private String officerWithoutPermission;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        buyerUuid = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        customerToken = TestJwts.forUser(buyerUuid).role("CUSTOMER").phoneNumber(BUYER_PHONE).sign(jwtSecret);
        superAdminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        UUID developer = UUID.randomUUID();
        readerToken = TestJwts.forUser(developer).loginIdentifier("dev@innbucks.co.zw")
                .role("PRODUCT_OFFICER").permissions(PlatformReader.REPORTS_READ).sign(jwtSecret);
        officerWithoutPermission = TestJwts.forUser(developer).loginIdentifier("dev@innbucks.co.zw")
                .role("PRODUCT_OFFICER").sign(jwtSecret);
    }

    @Test
    @DisplayName("the reader reads another seller's listings, orders, parcels, money and sellers")
    void grantedGetsReadAnySeller() throws Exception {
        String listingId = publishListing("Wireless Earbuds");
        Map<String, String> order = placePaidOrder(listingId);
        String tracking = jdbc.queryForObject(
                "SELECT tracking_code FROM order_fulfilment WHERE order_id = ?::uuid", String.class,
                order.get("orderId"));

        as(readerToken, get("/marketplace/listings/{id}", listingId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()));
        as(readerToken, get("/marketplace/listings/mine").param("merchantId", merchantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(listingId));
        as(readerToken, get("/marketplace/orders")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/orders/{id}", order.get("orderId"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderRef").value(order.get("orderRef")));
        as(readerToken, get("/marketplace/orders/{id}/fulfilments/{fid}/tracking",
                order.get("orderId"), order.get("fulfilmentId"))).andExpect(status().isOk());
        as(readerToken, get("/marketplace/fulfilments").param("merchantId", merchantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderRef").value(order.get("orderRef")));
        as(readerToken, get("/marketplace/fulfilments").param("q", order.get("orderRef")))
                .andExpect(status().isOk());
        as(readerToken, get("/marketplace/fulfilments/stats")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/fulfilments/tracking/{code}", tracking)).andExpect(status().isOk());
        as(readerToken, get("/marketplace/settlements")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].merchantId").value(merchantId.toString()));
        as(readerToken, get("/marketplace/settlements/summary")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/settlements/statement")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/settlements/disputes")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/settlements/stale")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/reports")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/admin/sellers")).andExpect(status().isOk());
        as(readerToken, get("/marketplace/admin/sellers/{id}/collection", merchantId)).andExpect(status().isOk());
        as(readerToken, get("/marketplace/admin/sellers/{id}/collection-points", merchantId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("every write is refused: listing, order, dispute decision, payout, refund, onboarding")
    void writesAreRefused() throws Exception {
        String listingId = publishListing("Bluetooth Speaker");
        UUID anyId = UUID.randomUUID();

        as(readerToken, post("/marketplace/listings").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Not mine","description":"x","categoryCode":"electronics",
                         "priceCents":1550,"stockQty":1,"merchantId":"%s"}""".formatted(merchantId)))
                .andExpect(status().isForbidden());
        as(readerToken, patch("/marketplace/listings/{id}/status", listingId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden());
        as(readerToken, post("/marketplace/orders").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}]}".formatted(listingId)))
                .andExpect(status().isForbidden());
        as(readerToken, post("/marketplace/orders/{id}/cancel", anyId)).andExpect(status().isForbidden());
        as(readerToken, patch("/marketplace/settlements/disputes/{id}", anyId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REFUND\"}"))
                .andExpect(status().isForbidden());
        as(readerToken, post("/marketplace/settlements/pay-out").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":\"%s\",\"payoutReference\":\"X\"}".formatted(merchantId)))
                .andExpect(status().isForbidden());
        as(readerToken, post("/marketplace/settlements/{id}/refund", anyId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"refundReference\":\"X\"}"))
                .andExpect(status().isForbidden());
        as(readerToken, put("/marketplace/admin/sellers/{id}/approve", merchantId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        as(readerToken, post("/marketplace/fulfilments/{id}/dispatch", anyId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // Nothing moved: the listing is still live.
        as(merchantToken, get("/marketplace/listings/{id}", listingId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("payout destinations, the payout report and buyer look-ups stay out of reach")
    void excludedReadsAreRefused() throws Exception {
        as(merchantToken, put("/marketplace/sellers/me/payout-destination")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"method":"BANK","accountName":"R. Chikwanha","bankName":"CBZ Bank",
                         "accountNumber":"%s"}""".formatted(ACCOUNT_NUMBER)))
                .andExpect(status().isOk());

        as(readerToken, get("/marketplace/admin/sellers/{id}/payout-destination", merchantId))
                .andExpect(status().isForbidden());
        as(readerToken, get("/marketplace/settlements/payout-report")).andExpect(status().isForbidden());
        as(readerToken, get("/marketplace/sellers/me/payout-destination")).andExpect(status().isForbidden());
        // The seller directory the reader may read carries no payout details at all.
        as(readerToken, get("/marketplace/admin/sellers")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(ACCOUNT_NUMBER))))
                .andExpect(content().string(not(containsString("R. Chikwanha"))));

        // Buyer-level look-ups are customer support's, logged there.
        as(readerToken, get("/marketplace/orders").param("buyerUuid", buyerUuid.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("buyer_lookup_not_allowed"));
        as(readerToken, get("/marketplace/fulfilments").param("q", "0771 234 567"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("buyer_lookup_not_allowed"));
        as(readerToken, get("/marketplace/fulfilments").param("q", "Tariro"))
                .andExpect(status().isForbidden());
        as(readerToken, get("/marketplace/orders/mine")).andExpect(status().isForbidden());
        as(readerToken, get("/marketplace/cart")).andExpect(status().isForbidden());
        as(readerToken, post("/marketplace/support/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"MKT-0000\"}")).andExpect(status().isForbidden());
        // SUPER_ADMIN keeps every one of them.
        as(superAdminToken, get("/marketplace/orders").param("buyerUuid", buyerUuid.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the reader sees buyer contact redacted; SUPER_ADMIN sees it whole")
    void buyerContactIsRedactedForTheReader() throws Exception {
        Map<String, String> order = placePaidGift(publishListing("Gift Hamper"));
        // A destination on the order, written straight to the row: the view
        // renders whatever the order carries.
        jdbc.update("""
                UPDATE market_order SET delivery_recipient_name = 'Tariro Moyo',
                       delivery_recipient_msisdn = ?, delivery_line1 = '14 Samora Machel Ave',
                       delivery_line2 = 'Flat 3B', delivery_city = 'Harare', delivery_area = 'Avondale',
                       delivery_landmark = 'Blue gate' WHERE id = ?::uuid""",
                BUYER_PHONE, order.get("orderId"));

        as(readerToken, get("/marketplace/orders/{id}", order.get("orderId"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipient.name").value("Rudo Chikwanha"))
                .andExpect(jsonPath("$.data.recipient.msisdn").value("****3344"))
                .andExpect(jsonPath("$.data.recipient.message").doesNotExist())
                .andExpect(jsonPath("$.data.deliveryAddress.recipientMsisdn").value("****4567"))
                .andExpect(jsonPath("$.data.deliveryAddress.line1").doesNotExist())
                .andExpect(jsonPath("$.data.deliveryAddress.landmark").doesNotExist())
                .andExpect(jsonPath("$.data.deliveryAddress.city").value("Harare"))
                .andExpect(content().string(not(containsString(BUYER_PHONE))))
                .andExpect(content().string(not(containsString(GIFT_PHONE))));

        as(superAdminToken, get("/marketplace/orders/{id}", order.get("orderId"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipient.msisdn").value(GIFT_PHONE))
                .andExpect(jsonPath("$.data.recipient.message").value("Happy birthday"))
                .andExpect(jsonPath("$.data.deliveryAddress.line1").value("14 Samora Machel Ave"));
    }

    @Test
    @DisplayName("the same token without the permission reads nothing")
    void withoutThePermissionNothingIsGranted() throws Exception {
        String listingId = publishListing("Phone Case");
        for (MockHttpServletRequestBuilder read : List.of(
                get("/marketplace/listings/{id}", listingId),
                get("/marketplace/listings/mine"),
                get("/marketplace/orders"),
                get("/marketplace/fulfilments"),
                get("/marketplace/fulfilments/stats"),
                get("/marketplace/settlements"),
                get("/marketplace/settlements/summary"),
                get("/marketplace/settlements/disputes"),
                get("/marketplace/reports"),
                get("/marketplace/admin/sellers"))) {
            as(officerWithoutPermission, read).andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private Map<String, String> placePaidOrder(String listingId) throws Exception {
        return placePaid("{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}]}".formatted(listingId));
    }

    private Map<String, String> placePaidGift(String listingId) throws Exception {
        return placePaid("""
                {"items":[{"listingId":"%s","quantity":1}],
                 "recipient":{"name":"Rudo Chikwanha","msisdn":"%s","message":"Happy birthday"}}"""
                .formatted(listingId, GIFT_PHONE));
    }

    private Map<String, String> placePaid(String body) throws Exception {
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}".formatted(orderRef)))
                .andExpect(status().isOk());
        String fulfilmentId = jdbc.queryForObject(
                "SELECT id::text FROM order_fulfilment WHERE order_id = ?::uuid", String.class, orderId);
        return Map.of("orderId", orderId, "orderRef", orderRef, "fulfilmentId", fulfilmentId);
    }

    private String publishListing(String title) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"Portable and sturdy","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10}""".formatted(title)))
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
}
