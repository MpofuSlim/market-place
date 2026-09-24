package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.fulfilment.CollectionOverdueSweeper;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Item 2 end to end over the real security chain and a real Postgres: the
 * "delivers to my town" browse, a buyer cancelling a paid parcel before it is
 * sent, and the seller's bell — each alert fired AFTER commit on the async
 * pool, typed and linked, to every runner of the selling business.
 *
 * <p>Shares {@link NotificationFlowIT}'s mocked channels (and therefore its
 * Spring context): the user-service gateway and the admin resolver are the
 * two seams a seller alert crosses.
 */
@Import(NotificationFlowIT.MockNotifyChannels.class)
class SellerAlertsAndBuyerCancelIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private UserNotifyGateway userNotifyGateway;

    @Autowired
    private MerchantAdminResolver adminResolver;

    @Autowired
    private SmsNotificationClient sms;

    @Autowired
    private WhatsAppNotificationClient whatsApp;

    @Autowired
    private CollectionOverdueSweeper collectionOverdueSweeper;

    private UUID merchantId;
    private UUID sellerAdmin;
    private String merchantToken;
    private String customerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        Mockito.reset(userNotifyGateway, adminResolver, sms, whatsApp);
        merchantId = UUID.randomUUID();
        sellerAdmin = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(sellerAdmin, merchantId, jwtSecret);
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
        when(adminResolver.adminUserUuids(merchantId)).thenReturn(List.of(sellerAdmin));
        when(userNotifyGateway.notify(any(), any(UserNotice.class))).thenReturn(true);
        when(userNotifyGateway.notify(any(), anyString(), anyString())).thenReturn(true);
        when(sms.isConfigured()).thenReturn(true);
    }

    // ------------------------------------------------------------------
    // Delivers to my town
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deliversTo returns only listings covering that town, once each, whatever else they cover")
    void deliversToFiltersOnCoverage() throws Exception {
        String harareAndBulawayo = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 500 },
                 { "townCode": "bulawayo", "feeCents": 900 }]""");
        String harareOnly = publishListing("Garden Hose 15m", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        String collectionOnly = publishListing("Clay Pot", "[]");

        String harare = mockMvc.perform(get("/marketplace/catalog")
                        .param("deliversTo", "Harare").param("merchantId", merchantId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // An EXISTS, not a join: the two-town listing appears ONCE, not once
        // per town it covers — or paging would repeat it.
        assertThat(JsonPath.<List<String>>read(harare, "$.data.items[*].id"))
                .containsExactlyInAnyOrder(harareAndBulawayo, harareOnly);

        String bulawayo = mockMvc.perform(get("/marketplace/catalog")
                        .param("deliversTo", "bulawayo").param("merchantId", merchantId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(bulawayo, "$.data.items[*].id"))
                .containsExactly(harareAndBulawayo);

        String mutare = mockMvc.perform(get("/marketplace/catalog")
                        .param("deliversTo", "mutare").param("merchantId", merchantId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(mutare, "$.data.items")).isEmpty();

        // Unfiltered, the collection-only listing is still for sale.
        String all = mockMvc.perform(get("/marketplace/catalog")
                        .param("merchantId", merchantId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(all, "$.data.items[*].id")).contains(collectionOnly);

        // A town we do not know is a 400 naming the parameter — never an empty
        // page that reads as "nobody delivers to you".
        mockMvc.perform(get("/marketplace/catalog").param("deliversTo", "atlantis"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_town"));
    }

    // ------------------------------------------------------------------
    // Buyer cancels before dispatch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buyer cancels a paid parcel: stock back, refund queued, seller alerted, nobody SMSes the buyer")
    void buyerCancelsBeforeDispatch() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> order = placePaidOrder(listingId, "buyer-cancel-1", 2);
        assertThat(stockOf(listingId)).isEqualTo(8);
        Mockito.clearInvocations(sms);

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ordered the wrong size\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Parcel cancelled - your refund is on its way"))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.fulfilments[0].status").value("UNFULFILLED"))
                .andExpect(jsonPath("$.data.fulfilments[0].unfulfilledBy").value("BUYER"))
                .andExpect(jsonPath("$.data.fulfilments[0].unfulfilledReason")
                        .value("Ordered the wrong size"))
                .andExpect(jsonPath("$.data.fulfilments[0].trackingStatus").value("CANCELLED"));

        assertThat(stockOf(listingId)).isEqualTo(10);

        // The seller's card and earnings row say it was the BUYER, not them.
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].closedBy").value("BUYER_CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].settlementStatus").value("REFUND_DUE"));
        mockMvc.perform(get("/marketplace/settlements")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.items[0].status").value("REFUND_DUE"))
                .andExpect(jsonPath("$.data.items[0].closedBy").value("BUYER_CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].refundReason").value("Ordered the wrong size"));

        // The tracking screen names who cancelled.
        mockMvc.perform(get("/marketplace/orders/{id}/fulfilments/{fid}/tracking",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.trackingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledBy").value("BUYER"));

        // The seller is told, after commit, typed and linked to the parcel.
        UserNotice notice = awaitSellerNotice("ORDER_CANCELLED_BY_BUYER");
        assertThat(notice.deepLink()).isEqualTo("/marketplace/parcels?q=" + order.get("orderRef"));
        assertThat(notice.subjectKind()).isEqualTo("PARCEL");
        assertThat(notice.subjectId()).isEqualTo(order.get("fulfilmentId"));
        assertThat(notice.message()).contains("Reason - Ordered the wrong size.");
        // The buyer did it on the screen in front of them: no SMS.
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());

        // In the tamper-evident chain as the buyer's act; the free text is not.
        String audit = jdbc.queryForObject("SELECT metadata FROM audit_events "
                + "WHERE event_type = 'FULFILMENT_UNFULFILLED'", String.class);
        assertThat(JsonPath.<Boolean>read(audit, "$.cancelledByBuyer")).isTrue();
        assertThat(audit).doesNotContain("wrong size");
        assertThat(jdbc.queryForObject("SELECT unfulfilled_by FROM order_fulfilment WHERE id = ?::uuid",
                String.class, order.get("fulfilmentId"))).isEqualTo("BUYER");

        // Once is enough: a second tap is a clean 409, not a second restock.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_not_cancellable"));
        assertThat(stockOf(listingId)).isEqualTo(10);
    }

    @Test
    @DisplayName("too late once dispatched; someone else's parcel is a 404; sellers cannot use it")
    void cancelIsRefusedWhenItCannotMeanAnything() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> order = placePaidOrder(listingId, "buyer-cancel-late", 1);

        // Another customer — and the seller — cannot reach it.
        String stranger = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263772222222").sign(jwtSecret);
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Ready at the counter\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_not_cancellable"));
        assertThat(stockOf(listingId)).isEqualTo(9);
    }

    @Test
    @DisplayName("the database refuses an UNFULFILLED parcel that names nobody")
    void unfulfilledMustNameWhoEndedIt() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> order = placePaidOrder(listingId, "buyer-cancel-check", 1);

        assertThatThrownBy(() -> jdbc.update("UPDATE order_fulfilment SET status = 'UNFULFILLED' "
                + "WHERE id = ?::uuid", order.get("fulfilmentId")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE order_fulfilment SET unfulfilled_by = 'BUYER' "
                + "WHERE id = ?::uuid", order.get("fulfilmentId")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // The seller's bell
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a buyer's dispute alerts the seller, typed PARCEL_DISPUTED")
    void disputeAlertsTheSeller() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> order = placePaidOrder(listingId, "alert-dispute-1", 1);

        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/dispute",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\",\"detail\":\"Nothing yet\"}"))
                .andExpect(status().isOk());

        UserNotice notice = awaitSellerNotice("PARCEL_DISPUTED");
        assertThat(notice.severity()).isEqualTo("WARNING");
        assertThat(notice.subjectId()).isEqualTo(order.get("fulfilmentId"));
        assertThat(notice.message()).contains("not received")
                // The buyer's free text is for the operator, not the seller's bell.
                .doesNotContain("Nothing yet");

        // And a disputed parcel is the operator's: the buyer cannot now cancel it.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/cancel",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parcel_disputed"));
    }

    @Test
    @DisplayName("a recorded payout alerts the seller with the amount and reference")
    void payoutAlertsTheSeller() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> order = placePaidOrder(listingId, "alert-payout-1", 2);
        // The buyer's own "received" releases the money at once.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/marketplace/settlements/pay-out")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"%s\",\"payoutReference\":\"IB-PAY-2209\"}"
                                .formatted(merchantId)))
                .andExpect(status().isOk());

        UserNotice notice = awaitSellerNotice("PAYOUT_SENT");
        assertThat(notice.severity()).isEqualTo("SUCCESS");
        assertThat(notice.subject()).isEqualTo("Payout sent - USD 31.00");
        assertThat(notice.message()).isEqualTo(
                "We have paid you USD 31.00 for 1 parcel. Payout reference IB-PAY-2209.");
        assertThat(notice.subjectKind()).isEqualTo("PAYOUT");
        assertThat(notice.subjectId()).isEqualTo("IB-PAY-2209");
        assertThat(notice.deepLink()).isEqualTo("/marketplace/earnings");
    }

    @Test
    @DisplayName("a buyer the SMS could not reach is the seller's to tell")
    void anUnreachedBuyerAlertsTheSeller() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> order = placePaidOrder(listingId, "alert-unreached-1", 1);
        Mockito.doThrow(new NotificationDeliveryException("gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Ready at the counter\"}"))
                .andExpect(status().isOk());

        UserNotice notice = awaitSellerNotice("BUYER_NOT_NOTIFIED");
        assertThat(notice.message()).contains("that it is ready to collect");
        assertThat(notice.subjectId()).isEqualTo(order.get("fulfilmentId"));
        // And the card says so too (V15), recorded by the same listener.
        await().atMost(Duration.ofSeconds(5)).until(() -> "FAILED".equals(jdbc.queryForObject(
                "SELECT buyer_notice_outcome FROM order_fulfilment WHERE id = ?::uuid",
                String.class, order.get("fulfilmentId"))));
    }

    @Test
    @DisplayName("a collection left at the counter past the threshold is alerted ONCE")
    void overdueCollectionIsAlertedOnce() throws Exception {
        String listingId = publishListing("Solar Lantern 20W", """
                [{ "townCode": "harare", "feeCents": 0 }]""");
        Map<String, String> waiting = placePaidOrder(listingId, "alert-overdue-1", 1);
        Map<String, String> fresh = placePaidOrder(listingId, "alert-overdue-2", 1);
        for (Map<String, String> order : List.of(waiting, fresh)) {
            mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.get("fulfilmentId"))
                            .header("Authorization", "Bearer " + merchantToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"Ready at the counter\"}"))
                    .andExpect(status().isOk());
        }
        // Set aside nine days ago — past the 7-day threshold.
        jdbc.update("UPDATE order_fulfilment SET dispatched_at = now() - interval '9 days' "
                + "WHERE id = ?::uuid", waiting.get("fulfilmentId"));

        // The sweep runs synchronously here; no await needed.
        collectionOverdueSweeper.sweep();

        List<UserNotice> overdue = sellerNotices("COLLECTION_OVERDUE");
        assertThat(overdue).singleElement().satisfies(n -> {
            assertThat(n.subjectId()).isEqualTo(waiting.get("fulfilmentId"));
            assertThat(n.message()).contains("ready to collect for 7 days");
        });
        assertThat(jdbc.queryForObject("SELECT collection_overdue_alerted_at IS NOT NULL "
                + "FROM order_fulfilment WHERE id = ?::uuid", Boolean.class,
                waiting.get("fulfilmentId"))).isTrue();

        // Tomorrow's sweep says nothing new: once per parcel.
        collectionOverdueSweeper.sweep();
        assertThat(sellerNotices("COLLECTION_OVERDUE")).hasSize(1);
    }

    // ------------------------------------------------------------------

    /** Everything the seller's bell has been sent so far. */
    private List<UserNotice> sellerNotices() {
        ArgumentCaptor<UserNotice> captor = ArgumentCaptor.forClass(UserNotice.class);
        verify(userNotifyGateway, atLeast(0)).notify(eq(sellerAdmin), captor.capture());
        return captor.getAllValues();
    }

    private List<UserNotice> sellerNotices(String type) {
        return sellerNotices().stream().filter(n -> type.equals(n.type())).toList();
    }

    /** Waits for the async after-commit alert of one type and returns it —
     *  exactly one, or the test fails. Keyed on the TYPE so the order-paid
     *  notice every paid order also sends cannot race the assertion. */
    private UserNotice awaitSellerNotice(String type) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !sellerNotices(type).isEmpty());
        List<UserNotice> matching = sellerNotices(type);
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private int stockOf(String listingId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }

    private Map<String, String> placePaidOrder(String listingId, String idempotencyKey,
                                               int quantity) throws Exception {
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"listingId\":\"%s\",\"quantity\":%d}]}"
                                .formatted(listingId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":%d}"
                                .formatted(idempotencyKey, 1550L * quantity)))
                .andExpect(status().isOk());
        // Let the order-paid side effects (buyer SMS, seller ORDER_PAID notice)
        // land before the test goes on, so they cannot race what it asserts.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(sms, atLeastOnce()).sendSms(anyString(), anyString(), eq(orderRef));
            assertThat(sellerNotices("ORDER_PAID"))
                    .anyMatch(n -> orderId.equals(n.subjectId()));
        });

        String paid = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paid, "$.data.fulfilments[0].id");
        return Map.of("orderId", orderId, "orderRef", orderRef, "fulfilmentId", fulfilmentId);
    }

    private String publishListing(String title, String deliveryTowns) throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "description": "Portable and sturdy",
                                  "categoryCode": "electronics",
                                  "priceCents": 1550,
                                  "stockQty": 10,
                                  "deliveryTowns": %s
                                }""".formatted(title, deliveryTowns)))
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
}
