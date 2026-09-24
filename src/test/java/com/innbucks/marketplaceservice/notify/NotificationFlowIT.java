package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ONLY proof the notification wiring is real: a PAID transition driven
 * over the real internal S2S surface (real SecurityFilterChain, real Postgres,
 * real transaction) fires {@code OrderPaidNotificationListener} AFTER the
 * commit, on the async pool, with the composed buyer SMS — and a buyer cancel
 * that restocks a sold-out favorited listing fires the restock alert through
 * {@link UserNotifyGateway}. The notify clients are replaced by
 * {@code @TestConfiguration} {@code @Primary} Mockito mocks (the fleet's
 * mock-bean shape — {@code @MockitoBean} is unreliable on Boot 4), so the
 * assertions await the ASYNC call the AFTER_COMMIT listener makes — the
 * middleware's {@code aCompletedDepositAlertsTheCustomer} idea.
 */
@Import(NotificationFlowIT.MockNotifyChannels.class)
class NotificationFlowIT extends PostgresTestContainer {

    @TestConfiguration
    static class MockNotifyChannels {
        @Bean
        @Primary
        SmsNotificationClient smsNotificationClientMock() {
            return Mockito.mock(SmsNotificationClient.class);
        }

        @Bean
        @Primary
        WhatsAppNotificationClient whatsAppNotificationClientMock() {
            return Mockito.mock(WhatsAppNotificationClient.class);
        }

        @Bean
        @Primary
        UserNotifyGateway userNotifyGatewayMock() {
            return Mockito.mock(UserNotifyGateway.class);
        }

        /** Who runs a selling organization lives in user-service; here the
         *  seller-alert ITs name the recipients. Unstubbed it answers an empty
         *  list — exactly what the real resolver answers on a miss. */
        @Bean
        @Primary
        MerchantAdminResolver merchantAdminResolverMock() {
            return Mockito.mock(MerchantAdminResolver.class);
        }
    }

    /** Real PNG signature + filler — the publish gate requires a primary image. */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private SmsNotificationClient sms;

    @Autowired
    private WhatsAppNotificationClient whatsApp;

    @Autowired
    private UserNotifyGateway userNotifyGateway;

    private UUID buyerUuid;
    private String customerToken;

    @BeforeEach
    void resetMocksAndMintTokens() {
        Mockito.reset(sms, whatsApp, userNotifyGateway);
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(false);
        when(userNotifyGateway.notify(Mockito.any(), anyString(), anyString())).thenReturn(true);
        buyerUuid = UUID.randomUUID();
        customerToken = TestJwts.customer(buyerUuid, jwtSecret);
    }

    @Test
    void confirmPaymentFiresTheBuyerSmsAfterCommit() throws Exception {
        UUID listingId = seedActiveListing(10);
        String orderBody = """
                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":2}]}"""
                .formatted(listingId);
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "notify-flow-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        // Order creation alone must notify NOBODY.
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":3100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // AFTER_COMMIT + @Async: the SMS lands on the notification executor
        // after the confirming transaction commits — await it.
        String expected = "Your InnBucks Marketplace order " + orderRef
                + " (USD 31.00) is confirmed. Ref " + orderRef;
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(sms).sendSms("+263771234567", expected, orderRef));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());

        // A replayed confirm (idempotent 200, no transition) must NOT notify twice.
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":3100}"))
                .andExpect(status().isOk());
        // Give a wrongly-fired async task a moment to surface, then pin ONE send.
        Thread.sleep(300);
        verify(sms, times(1)).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void cancellingTheLastStockFiresARestockAlertToTheFavoriter() throws Exception {
        UUID listingId = seedActiveListing(1);

        // The buyer favorites the listing, then buys ALL remaining stock.
        mockMvc.perform(put("/marketplace/favorites/{id}", listingId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        String orderBody = """
                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                .formatted(listingId);
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "notify-flow-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");

        // Cancel returns the last unit: 0 -> 1 publishes ListingRestocked and
        // the AFTER_COMMIT async listener notifies the favoriter through
        // user-service.
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(userNotifyGateway).notify(buyerUuid,
                        "Back in stock on InnBucks Marketplace",
                        "Back in stock. Solar Lantern 20W - USD 15.50 on InnBucks Marketplace"));
        // The order-paid channels stay silent on a cancel.
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A listing whose every option was sold out alerts its favoriter ONCE, quoting the "
            + "from-price, when one size is restocked - and not again when another size comes back "
            + "while the first still has stock")
    void restockingOneOptionOfASoldOutListingAlertsOnceWithTheFromPrice() throws Exception {
        String sellerToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        String listingId = publishSoldOutListingWithOptions(sellerToken);
        String medium = optionIdOf(listingId, "M");
        String large = optionIdOf(listingId, "L");
        mockMvc.perform(put("/marketplace/favorites/{id}", listingId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        // Size M comes back: the LISTING's total moves 0 -> 3, which is what
        // "back in stock" means for a favourite. Its price is the lowest
        // option's, so the copy says "from".
        mockMvc.perform(patch("/marketplace/listings/{id}/variants/{variantId}/stock", listingId, medium)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQty\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(3));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(userNotifyGateway).notify(buyerUuid,
                        "Back in stock on InnBucks Marketplace",
                        "Back in stock. Cotton Crew Tee - from USD 19.99 on InnBucks Marketplace"));

        // Size L comes back while M already has stock: the listing was never
        // out, so a favoriter hearing "back in stock" again would be spam.
        mockMvc.perform(patch("/marketplace/listings/{id}/variants/{variantId}/stock", listingId, large)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQty\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(8));
        // Held for a window: a single immediate read would pass before a
        // wrongly-fired async alert had any chance to land.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(userNotifyGateway, times(1)).notify(eq(buyerUuid), anyString(), anyString()));
    }

    @Test
    void aSellerDispatchTellsTheBuyerAfterCommit() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID listingId = seedActiveListing(5, merchantId);
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "notify-flow-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0003\",\"amountCents\":1550}"))
                .andExpect(status().isOk());
        String paid = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");

        // The seller sets the collection aside at the counter.
        String sellerToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());

        String expected = "Your InnBucks Marketplace order " + orderRef + " is ready to collect. "
                + "Get your collection code in the app and show it at the counter. Ref " + orderRef;
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(sms).sendSms("+263771234567", expected, orderRef));
    }

    @Test
    void aRecordedRefundTellsTheBuyerTheMoneyHasLeft() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID listingId = seedActiveListing(5, merchantId);
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "notify-flow-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0004\",\"amountCents\":1550}"))
                .andExpect(status().isOk());
        String paid = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");

        // The seller cannot supply it: the parcel is CANCELLED and the money
        // queued back (REFUND_DUE) — nothing has left yet.
        String sellerToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        mockMvc.perform(post("/marketplace/fulfilments/{id}/unfulfillable", fulfilmentId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.settlementStatus").value("REFUND_DUE"));
        String settlements = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + sellerToken))
                .andReturn().getResponse().getContentAsString();
        String settlementId = JsonPath.read(settlements, "$.data.items[0].id");

        // The operator records the transfer they made: THAT is when the buyer
        // is told the money has gone, with the reference to look for.
        String adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/settlements/{id}/refund", settlementId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundReference\":\"IB-778812\"}"))
                .andExpect(status().isOk());

        String expected = "Your refund of USD 15.50 for InnBucks Marketplace order " + orderRef
                + " has been sent. Refund reference IB-778812. Ref " + orderRef;
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(sms).sendSms("+263771234567", expected, orderRef));
    }

    /**
     * A listing with options (V19) is created the way a seller creates one -
     * over HTTP, so its options and derived total come from the real editor -
     * and published. Every option starts at 0; XL costs more than the
     * listing price, which is therefore a "from" price.
     */
    private String publishSoldOutListingWithOptions(String sellerToken) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                                 "options":["Size"],
                                 "variants":[{"values":["M"],"stockQty":0},
                                             {"values":["L"],"stockQty":0},
                                             {"values":["XL"],"priceCents":2299,"stockQty":0}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.stockQty").value(0))
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return listingId;
    }

    private String optionIdOf(String listingId, String value) {
        return jdbc.queryForObject("""
                SELECT id::text FROM listing_variant
                 WHERE listing_id = ?::uuid AND option1_value = ?""",
                String.class, listingId, value);
    }

    private UUID seedActiveListing(int stockQty) {
        return seedActiveListing(stockQty, UUID.randomUUID());
    }

    private UUID seedActiveListing(int stockQty, UUID merchantId) {
        Instant now = Instant.now();
        Listing listing = Listing.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .title("Solar Lantern 20W")
                .priceCents(1550L)
                .currency("USD")
                .stockQty(stockQty)
                .status(ListingStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        listingRepository.save(listing);
        return listing.getId();
    }
}
