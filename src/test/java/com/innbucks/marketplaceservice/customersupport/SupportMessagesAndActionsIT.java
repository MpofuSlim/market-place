package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.notify.NotificationDeliveryException;
import com.innbucks.marketplaceservice.notify.NotificationFlowIT;
import com.innbucks.marketplaceservice.notify.OrderNotificationComposer;
import com.innbucks.marketplaceservice.notify.SmsNotificationClient;
import com.innbucks.marketplaceservice.notify.WhatsAppNotificationClient;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Support messages (V23) and support actions end to end, over the real
 * security chain and a real Postgres, with the SMS / WhatsApp clients mocked
 * (shared with {@code NotificationFlowIT}, so one Spring context serves both):
 * a message only ever reaches a number on the record, a refusal writes
 * nothing, the limits hold, the outcome is recorded honestly, a collection
 * code never reaches the agent, and every action runs the buyer's own rule and
 * leaves its reason as a note.
 */
@Import(NotificationFlowIT.MockNotifyChannels.class)
class SupportMessagesAndActionsIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};
    private static final String BUYER_PHONE = "+263771234567";
    private static final String GIFT_PHONE = "+263772345678";
    private static final String SIGNATURE = "\n- InnBucks Marketplace Support";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private SmsNotificationClient sms;

    @Autowired
    private WhatsAppNotificationClient whatsApp;

    private UUID merchantId;
    private UUID buyerUuid;
    private UUID agentUuid;
    private UUID supervisorUuid;
    private String merchantToken;
    private String customerToken;
    private String agentToken;
    private String supervisorToken;
    private String readOnlyToken;

    @BeforeEach
    void setUp() {
        Mockito.reset(sms, whatsApp);
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        merchantId = UUID.randomUUID();
        buyerUuid = UUID.randomUUID();
        agentUuid = UUID.randomUUID();
        supervisorUuid = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        customerToken = TestJwts.forUser(buyerUuid).role("CUSTOMER").phoneNumber(BUYER_PHONE).sign(jwtSecret);
        agentToken = TestJwts.forUser(agentUuid).loginIdentifier("tariro.moyo@innbucks.co.zw")
                .role("CALL_CENTER_AGENT")
                .permissions(SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.MESSAGES_SEND)
                .sign(jwtSecret);
        supervisorToken = TestJwts.forUser(supervisorUuid).loginIdentifier("chipo.supervisor@innbucks.co.zw")
                .role("CALL_CENTER_SUPERVISOR")
                .permissions(SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.SUPERVISE,
                        SupportPermissions.MESSAGES_SEND)
                .sign(jwtSecret);
        readOnlyToken = TestJwts.forUser(UUID.randomUUID()).loginIdentifier("viewer@innbucks.co.zw")
                .permissions(SupportPermissions.READ).sign(jwtSecret);
    }

    // ------------------------------------------------------------------
    // Typed messages
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a typed message goes to the payer on record, signed, and is recorded, logged and audited")
    void aTypedMessageGoesToTheNumberOnRecord() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing(), null);
        String text = "Hello, the seller confirms your parcel is ready from 10am tomorrow.";

        send(agentToken, message("ORDER", order.get("orderId"), "BUYER", null, null, text))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.data.outcome").value("SENT"))
                .andExpect(jsonPath("$.data.deliveredVia").value("SMS"))
                .andExpect(jsonPath("$.data.recipientRole").value("BUYER"))
                .andExpect(jsonPath("$.data.recipient").value("****4567"))
                .andExpect(jsonPath("$.data.text").value(text + SIGNATURE))
                .andExpect(jsonPath("$.data.sentBy.login").value("tariro.moyo@innbucks.co.zw"))
                .andExpect(content().string(not(containsString(BUYER_PHONE))));

        verify(sms).sendSms(eq(BUYER_PHONE), eq(text + SIGNATURE), startsWith("MKT-SUP-"));
        assertThat(jdbc.queryForObject("SELECT body FROM support_message", String.class)).isEqualTo(text + SIGNATURE);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM support_activity WHERE action = 'MESSAGE_SENT' AND agent_uuid = ?",
                Long.class, agentUuid.toString())).isEqualTo(1L);
        String audit = jdbc.queryForObject(
                "SELECT metadata FROM audit_events WHERE event_type = 'SUPPORT_MESSAGE_SENT' AND actor_uuid = ?",
                String.class, agentUuid.toString());
        assertThat(audit).contains("\"outcome\":\"SENT\"").contains("\"recipientRole\":\"BUYER\"")
                .doesNotContain(BUYER_PHONE).doesNotContain("ready from 10am");

        // The buyer's history includes what was said about their orders, and
        // the 360s carry the newest messages.
        mockMvc.perform(get("/marketplace/support/messages").header("Authorization", "Bearer " + readOnlyToken)
                        .param("subjectKind", "BUYER").param("subjectId", buyerUuid.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].subjectKind").value("ORDER"));
        mockMvc.perform(get("/marketplace/support/buyers/{id}", buyerUuid).header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.total").value(1));
        mockMvc.perform(get("/marketplace/support/orders/{key}", order.get("orderRef"))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.latest[0].kind").value("CUSTOM"));
    }

    @Test
    @DisplayName("on a buyer, the agent may SELECT one of the buyer's own numbers, never supply another")
    void aBuyersNumberIsSelectedNeverSupplied() throws Exception {
        placePaidOrder(publishListing(), null);

        send(agentToken, message("BUYER", buyerUuid.toString(), null, "0771 234 567", null, "Hello"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subjectKind").value("BUYER"))
                .andExpect(jsonPath("$.data.recipient").value("****4567"));
        send(agentToken, message("BUYER", buyerUuid.toString(), null, "+263779999999", null, "Hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("recipient_not_on_record"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_message", Long.class)).isEqualTo(1L);
        verify(sms, never()).sendSms(eq("+263779999999"), anyString(), anyString());
    }

    @Test
    @DisplayName("preview shows the SMS-safe text and writes nothing; refusals write nothing either")
    void previewAndRefusalsWriteNothing() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing(), null);
        String orderId = order.get("orderId");

        mockMvc.perform(post("/marketplace/support/messages/preview")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("ORDER", orderId, "BUYER", null, null, "Your parcel — ready: collect today!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transliterated").value(true))
                .andExpect(jsonPath("$.data.text").value("Your parcel - ready collect today." + SIGNATURE))
                .andExpect(jsonPath("$.data.smsSegments").value(1))
                .andExpect(jsonPath("$.data.maxCharacters").value(459))
                .andExpect(jsonPath("$.data.whatsappText").value("Your parcel — ready: collect today!" + SIGNATURE));

        // Length is REPORTED by the preview (a live count), refused by the send.
        mockMvc.perform(post("/marketplace/support/messages/preview")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("ORDER", orderId, "BUYER", null, "SMS", "x".repeat(500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characters").value(500 + SIGNATURE.length()))
                .andExpect(jsonPath("$.data.maxCharacters").value(459))
                .andExpect(jsonPath("$.data.smsSegments").value(4));

        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "Pay here: https://bit.ly/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("link_not_allowed"))
                .andExpect(jsonPath("$.data.host").value("bit.ly"))
                .andExpect(jsonPath("$.message").value(containsString("'bit.ly'")));
        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "Track at https://www.innbucks.co.zw/track"))
                .andExpect(status().isCreated());
        send(agentToken, message("ORDER", orderId, "GIFT_RECIPIENT", null, null, "Hello"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("no_phone_on_record"));
        send(agentToken, message("ORDER", orderId, "BUYER", "+263771234567", null, "Hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_recipient"));
        send(agentToken, message("SELLER", merchantId.toString(), null, null, null, "Hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_recipient"));
        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "x".repeat(500)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("message_too_long"));
        send(agentToken, message("ORDER", UUID.randomUUID().toString(), "BUYER", null, null, "Hello"))
                .andExpect(status().isNotFound());

        // Only the one that was allowed is on record.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_message", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a customer number gets at most five support messages a day - the sixth is 429 and not sent")
    void theRecipientLimitHolds() throws Exception {
        String orderId = placePaidOrder(publishListing(), null).get("orderId");
        for (int i = 0; i < 5; i++) {
            send(agentToken, message("ORDER", orderId, "BUYER", null, "SMS", "Update " + i))
                    .andExpect(status().isCreated());
        }
        send(supervisorToken, message("ORDER", orderId, "BUYER", null, "SMS", "Update 6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("support_message_rate_limited"))
                .andExpect(jsonPath("$.data.scope").value("RECIPIENT"))
                .andExpect(jsonPath("$.data.limit").value(5))
                .andExpect(jsonPath("$.data.windowMinutes").value(1440));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_message", Long.class)).isEqualTo(5L);
        verify(sms, never()).sendSms(anyString(), eq("Update 6" + SIGNATURE), anyString());
    }

    @Test
    @DisplayName("SMS failing falls back to WhatsApp with the ORIGINAL text; both failing is 502 with the record")
    void theOutcomeIsRecordedHonestly() throws Exception {
        String orderId = placePaidOrder(publishListing(), null).get("orderId");
        doThrow(new NotificationDeliveryException("gateway 400")).when(sms)
                .sendSms(anyString(), anyString(), startsWith("MKT-SUP-"));

        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "Your parcel — ready"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deliveredVia").value("WHATSAPP"))
                .andExpect(jsonPath("$.data.text").value("Your parcel — ready" + SIGNATURE));
        verify(whatsApp).sendCustomNotification(BUYER_PHONE, "Your parcel — ready" + SIGNATURE);

        doThrow(new NotificationDeliveryException("gateway down")).when(whatsApp)
                .sendCustomNotification(anyString(), anyString());
        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "Second try"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("message_not_delivered"))
                .andExpect(jsonPath("$.data.outcome").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveredVia").doesNotExist())
                .andExpect(jsonPath("$.data.failureCode").value("sms_and_whatsapp_failed"));

        assertThat(jdbc.queryForList("SELECT outcome FROM support_message ORDER BY created_at", String.class))
                .containsExactly("SENT", "FAILED");
    }

    @Test
    @DisplayName("a channel this cell has not set up is 503 before anything is written")
    void anUnavailableChannelWritesNothing() throws Exception {
        String orderId = placePaidOrder(publishListing(), null).get("orderId");
        when(whatsApp.isConfigured()).thenReturn(false);

        send(agentToken, message("ORDER", orderId, "BUYER", null, "WHATSAPP", "Hello"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("channel_unavailable"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_message", Long.class)).isZero();
    }

    @Test
    @DisplayName("a completed message is final: the table refuses a rewrite and a delete")
    void aCompletedMessageCannotBeRewritten() throws Exception {
        String orderId = placePaidOrder(publishListing(), null).get("orderId");
        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "Hello")).andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbc.update("UPDATE support_message SET body = 'rewritten'"))
                .hasMessageContaining("is already SENT");
        assertThatThrownBy(() -> jdbc.update("UPDATE support_message SET recipient_msisdn = '+263779999999'"))
                .hasMessageContaining("support_message");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM support_message"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("typing messages needs customer-messages:send; the feed needs supervise")
    void messagingIsPermissionGated() throws Exception {
        String orderId = placePaidOrder(publishListing(), null).get("orderId");
        String noMessaging = TestJwts.forUser(UUID.randomUUID()).loginIdentifier("x@innbucks.co.zw")
                .permissions(SupportPermissions.READ, SupportPermissions.MANAGE).sign(jwtSecret);

        send(readOnlyToken, message("ORDER", orderId, "BUYER", null, null, "Hello")).andExpect(status().isForbidden());
        send(noMessaging, message("ORDER", orderId, "BUYER", null, null, "Hello")).andExpect(status().isForbidden());
        send(customerToken, message("ORDER", orderId, "BUYER", null, null, "Hello")).andExpect(status().isForbidden());
        mockMvc.perform(get("/marketplace/support/messages/feed").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden());

        send(agentToken, message("ORDER", orderId, "BUYER", null, null, "Hello")).andExpect(status().isCreated());
        mockMvc.perform(get("/marketplace/support/messages/feed").header("Authorization", "Bearer " + supervisorToken)
                        .param("agentUuid", agentUuid.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].recipient").value("****4567"));
    }

    // ------------------------------------------------------------------
    // Platform messages sent again
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fresh collection code reaches the collector, works at the counter, and never reaches the agent")
    void aFreshCollectionCodeNeverReachesTheAgent() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing(), GIFT_PHONE);
        String before = jdbc.queryForObject("SELECT collect_code_hash FROM order_fulfilment WHERE id = ?::uuid",
                String.class, order.get("fulfilmentId"));

        String response = mockMvc.perform(post("/marketplace/support/orders/{o}/fulfilments/{f}/collect-code",
                                order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("COLLECT_CODE"))
                .andExpect(jsonPath("$.data.recipientRole").value("GIFT_RECIPIENT"))
                .andExpect(jsonPath("$.data.text").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq(GIFT_PHONE), sent.capture(), startsWith("MKT-SUP-"));
        String code = sent.getValue().replaceAll("(?s).*?([0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}).*", "$1");
        assertThat(code).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}");
        assertThat(response).doesNotContain(code).doesNotContain(code.replace("-", ""));
        assertThat(jdbc.queryForObject("SELECT body FROM support_message", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT collect_code_hash FROM order_fulfilment WHERE id = ?::uuid",
                String.class, order.get("fulfilmentId"))).isNotNull().isNotEqualTo(before);

        // The code the collector received is the live one.
        mockMvc.perform(post("/marketplace/fulfilments/{id}/collect", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk());
        // ...and a closed parcel gets no code, on the buyer's own rule.
        mockMvc.perform(post("/marketplace/support/orders/{o}/fulfilments/{f}/collect-code",
                        order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));
    }

    @Test
    @DisplayName("the seller's parcel update is resent word for word, and the seller's card records the buyer was reached")
    void theParcelUpdateIsResent() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing(), null);
        String path = "/marketplace/support/orders/{o}/fulfilments/{f}/messages/parcel-update";

        mockMvc.perform(post(path, order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nothing_to_resend"));

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        String expected = OrderNotificationComposer.parcelDispatchedMessage(order.get("orderRef"),
                com.innbucks.marketplaceservice.delivery.DeliveryMethod.COLLECTION, false);

        mockMvc.perform(post(path, order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SMS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("PARCEL_UPDATE"))
                .andExpect(jsonPath("$.data.text").value(expected));
        verify(sms).sendSms(eq(BUYER_PHONE), eq(expected), startsWith("MKT-SUP-"));
        assertThat(jdbc.queryForMap("SELECT buyer_notice_kind, buyer_notice_outcome FROM order_fulfilment "
                + "WHERE id = ?::uuid", order.get("fulfilmentId")))
                .containsEntry("buyer_notice_kind", "READY_TO_COLLECT")
                .containsEntry("buyer_notice_outcome", "SMS");
    }

    @Test
    @DisplayName("the order confirmation is resent only for a paid order")
    void theConfirmationIsResentForAPaidOrder() throws Exception {
        String listingId = publishListing();
        Map<String, String> unpaid = placeOrder(listingId, null);
        mockMvc.perform(post("/marketplace/support/orders/{o}/messages/order-confirmation", unpaid.get("orderId"))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_not_paid"));

        Map<String, String> paid = placePaidOrder(listingId, null);
        mockMvc.perform(post("/marketplace/support/orders/{o}/messages/order-confirmation", paid.get("orderId"))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("ORDER_CONFIRMATION"))
                .andExpect(jsonPath("$.data.text").value(containsString(paid.get("orderRef"))));
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("support cancels an unpaid order: stock back, reason noted, audit names the agent")
    void supportCancelsAnUnpaidOrder() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placeOrder(listingId, null);

        action(agentToken, "/marketplace/support/orders/{o}/cancel", order.get("orderId"),
                "{\"reason\":\"Buyer called: chose the wrong item.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.notes.latest[0].body").value(containsString("chose the wrong item")));

        assertThat(jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid", Integer.class, listingId))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_activity WHERE action = 'ORDER_CANCELLED'",
                Long.class)).isEqualTo(1L);
        String audit = jdbc.queryForObject("SELECT metadata FROM audit_events WHERE event_type = 'ORDER_CANCELLED' "
                + "AND actor_uuid = ?", String.class, agentUuid.toString());
        assertThat(audit).contains("\"bySupport\":true").doesNotContain("wrong item");
    }

    @Test
    @DisplayName("a refused action leaves no note and no activity row behind")
    void aRefusedActionLeavesNothing() throws Exception {
        Map<String, String> paid = placePaidOrder(publishListing(), null);

        action(agentToken, "/marketplace/support/orders/{o}/cancel", paid.get("orderId"), "{\"reason\":\"Asked to\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_order_state"));
        action(agentToken, "/marketplace/support/orders/{o}/cancel", paid.get("orderId"), "{\"reason\":\"<b></b>\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("reason_required"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_note", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_activity WHERE action = 'ORDER_CANCELLED'",
                Long.class)).isZero();
    }

    @Test
    @DisplayName("support opens a dispute for the buyer: the buyer's dispute, the seller's money frozen")
    void supportOpensADisputeForTheBuyer() throws Exception {
        Map<String, String> order = placePaidOrder(publishListing(), null);

        mockMvc.perform(post("/marketplace/support/orders/{o}/fulfilments/{f}/dispute",
                                order.get("orderId"), order.get("fulfilmentId"))
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_RECEIVED\",\"detail\":\"Seller has not answered for a week.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.fulfilments[0].dispute.status").value("OPEN"));

        assertThat(jdbc.queryForObject("SELECT buyer_uuid::text FROM settlement_dispute", String.class))
                .isEqualTo(buyerUuid.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM merchant_settlement", String.class)).isEqualTo("DISPUTED");
        assertThat(jdbc.queryForObject("SELECT metadata FROM audit_events WHERE event_type = 'SETTLEMENT_DISPUTED'",
                String.class)).contains("\"bySupport\":true");
        assertThat(jdbc.queryForObject("SELECT actor_uuid FROM audit_events WHERE event_type = 'SETTLEMENT_DISPUTED'",
                String.class)).isEqualTo(agentUuid.toString());
    }

    @Test
    @DisplayName("only a supervisor cancels a paid parcel: it ends as the buyer's, stock back, refund queued")
    void onlyASupervisorCancelsAPaidParcel() throws Exception {
        String listingId = publishListing();
        Map<String, String> order = placePaidOrder(listingId, null);
        String path = "/marketplace/support/orders/{o}/fulfilments/" + order.get("fulfilmentId") + "/cancel";

        action(agentToken, path, order.get("orderId"), "{\"reason\":\"Buyer changed their mind\"}")
                .andExpect(status().isForbidden());
        action(supervisorToken, path, order.get("orderId"), "{\"reason\":\"Buyer changed their mind\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parcels[0].status").value("UNFULFILLED"));

        assertThat(jdbc.queryForMap("SELECT status, unfulfilled_by, unfulfilled_reason FROM order_fulfilment"))
                .containsEntry("status", "UNFULFILLED")
                .containsEntry("unfulfilled_by", "BUYER")
                .containsEntry("unfulfilled_reason", "Buyer changed their mind");
        assertThat(jdbc.queryForObject("SELECT status FROM merchant_settlement", String.class)).isEqualTo("REFUND_DUE");
        assertThat(jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid", Integer.class, listingId))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT metadata FROM audit_events WHERE event_type = 'FULFILMENT_UNFULFILLED' "
                + "AND actor_uuid = ?", String.class, supervisorUuid.toString())).contains("\"bySupport\":true");
    }

    // ------------------------------------------------------------------

    private ResultActions send(String token, String body) throws Exception {
        return mockMvc.perform(post("/marketplace/support/messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions action(String token, String path, String orderId, String body) throws Exception {
        return mockMvc.perform(post(path, orderId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String message(String kind, String subjectId, String recipient, String phone, String channel,
                                  String text) {
        StringBuilder json = new StringBuilder("{\"subjectKind\":\"").append(kind)
                .append("\",\"subjectId\":\"").append(subjectId).append('"');
        if (recipient != null) {
            json.append(",\"recipient\":\"").append(recipient).append('"');
        }
        if (phone != null) {
            json.append(",\"phone\":\"").append(phone).append('"');
        }
        if (channel != null) {
            json.append(",\"channel\":\"").append(channel).append('"');
        }
        return json.append(",\"text\":\"").append(text.replace("\"", "\\\"")).append("\"}").toString();
    }

    private Map<String, String> placeOrder(String listingId, String giftPhone) throws Exception {
        String recipient = giftPhone == null ? ""
                : ",\"recipient\":{\"name\":\"Gogo Chipo Moyo\",\"msisdn\":\"%s\"}".formatted(giftPhone);
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"listingId\":\"%s\",\"quantity\":1}]%s}".formatted(listingId, recipient)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Map.of("orderId", JsonPath.read(created, "$.data.id"),
                "orderRef", JsonPath.read(created, "$.data.orderRef"));
    }

    private Map<String, String> placePaidOrder(String listingId, String giftPhone) throws Exception {
        Map<String, String> order = placeOrder(listingId, giftPhone);
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", order.get("orderRef"))
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-%s\",\"amountCents\":1550}".formatted(order.get("orderRef"))))
                .andExpect(status().isOk());
        // The order-paid SMS is sent after commit, off the request thread; wait
        // for it so it cannot land in the middle of a test's own verification.
        verify(sms, timeout(5_000).atLeastOnce()).sendSms(anyString(), anyString(), eq(order.get("orderRef")));
        String fulfilmentId = jdbc.queryForObject(
                "SELECT id::text FROM order_fulfilment WHERE order_id = ?::uuid", String.class, order.get("orderId"));
        return Map.of("orderId", order.get("orderId"), "orderRef", order.get("orderRef"),
                "fulfilmentId", fulfilmentId);
    }

    private String publishListing() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Wireless Earbuds","description":"Portable and sturdy",
                                 "categoryCode":"electronics","priceCents":1550,"stockQty":10}"""))
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
