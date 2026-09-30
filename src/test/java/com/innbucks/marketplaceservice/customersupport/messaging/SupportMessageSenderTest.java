package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessageComposer.Composed;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportRecipients.Recipient;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.NotificationDeliveryException;
import com.innbucks.marketplaceservice.notify.SmsNotificationClient;
import com.innbucks.marketplaceservice.notify.WhatsAppNotificationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The send's channel logic and its honesty: what is attempted in which order,
 * which text each channel carries, what the row records, and that nothing
 * about the text or the number reaches the audit trail.
 */
class SupportMessageSenderTest {

    private static final SupportAgent AGENT = new SupportAgent("9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "a@b.co.zw");
    private static final String PHONE = "+263771234567";
    private static final Composed TEXTS = new Composed("sms text", "whatsapp — text", true);

    private final SmsNotificationClient sms = mock(SmsNotificationClient.class);
    private final WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
    private final SupportMessageLedger ledger = mock(SupportMessageLedger.class);
    private final AuditService audit = mock(AuditService.class);
    private final SupportMessageSender sender = new SupportMessageSender(sms, whatsApp, ledger, audit,
            mock(MarketplaceMetrics.class));
    private final Recipient recipient = new Recipient(SubjectKind.ORDER, UUID.randomUUID(), RecipientRole.BUYER, PHONE);

    @BeforeEach
    void setUp() {
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
        when(ledger.claim(any(), any(), any(), any(), any())).thenAnswer(inv -> new SupportMessage(AGENT,
                recipient.subjectKind(), recipient.subjectId(), PHONE, RecipientRole.BUYER, inv.getArgument(2),
                inv.getArgument(3), inv.getArgument(4), Instant.now()));
        when(ledger.complete(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SupportMessage m = new SupportMessage(AGENT, recipient.subjectKind(), recipient.subjectId(), PHONE,
                    RecipientRole.BUYER, SupportMessageKind.CUSTOM, MessageChannel.SMS_THEN_WHATSAPP, "sms text",
                    Instant.now());
            m.complete(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), Instant.now());
            return m;
        });
    }

    @Test
    @DisplayName("SMS first; the row starts with the SMS text and records SMS")
    void smsFirst() {
        var sent = sender.send(AGENT, recipient, SupportMessageKind.CUSTOM, MessageChannel.SMS_THEN_WHATSAPP, TEXTS);

        verify(sms).sendSms(eq(PHONE), eq("sms text"), org.mockito.ArgumentMatchers.startsWith("MKT-SUP-"));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(ledger).claim(AGENT, recipient, SupportMessageKind.CUSTOM, MessageChannel.SMS_THEN_WHATSAPP, "sms text");
        verify(ledger).complete(eq(AGENT), any(), eq(MessageOutcome.SENT), eq(DeliveredVia.SMS), eq("sms text"),
                eq(null));
        assertThat(sent.deliveredVia()).isEqualTo("SMS");
    }

    @Test
    @DisplayName("an SMS failure falls back to WhatsApp, which carries the ORIGINAL text")
    void fallback() {
        doThrow(new NotificationDeliveryException("400")).when(sms).sendSms(anyString(), anyString(), anyString());

        sender.send(AGENT, recipient, SupportMessageKind.CUSTOM, MessageChannel.SMS_THEN_WHATSAPP, TEXTS);

        verify(whatsApp).sendCustomNotification(PHONE, "whatsapp — text");
        verify(ledger).complete(eq(AGENT), any(), eq(MessageOutcome.SENT), eq(DeliveredVia.WHATSAPP),
                eq("whatsapp — text"), eq(null));
    }

    @Test
    @DisplayName("SMS alone never falls back; WhatsApp alone never tries SMS")
    void singleChannels() {
        doThrow(new NotificationDeliveryException("400")).when(sms).sendSms(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> sender.send(AGENT, recipient, SupportMessageKind.CUSTOM, MessageChannel.SMS, TEXTS))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("message_not_delivered");
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(ledger).complete(eq(AGENT), any(), eq(MessageOutcome.FAILED), eq(null), eq(null), eq("sms_failed"));

        sender.send(AGENT, recipient, SupportMessageKind.CUSTOM, MessageChannel.WHATSAPP, TEXTS);
        verify(ledger).claim(AGENT, recipient, SupportMessageKind.CUSTOM, MessageChannel.WHATSAPP,
                "whatsapp — text");
    }

    @Test
    @DisplayName("both failing is a 502 carrying the FAILED record; the audit names neither text nor number")
    void bothFail() {
        doThrow(new NotificationDeliveryException("400")).when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("down")).when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatThrownBy(() -> sender.send(AGENT, recipient, SupportMessageKind.CUSTOM,
                MessageChannel.SMS_THEN_WHATSAPP, TEXTS))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(502));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditEventType.SUPPORT_MESSAGE_SENT), eq(AGENT.uuid()), anyString(), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("outcome", "FAILED")
                .containsEntry("failureCode", "sms_and_whatsapp_failed");
        assertThat(metadata.getValue().toString()).doesNotContain(PHONE).doesNotContain("sms text");
    }

    @Test
    @DisplayName("no usable channel is 503 before anything is claimed")
    void unavailable() {
        when(sms.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> sender.requireChannel(MessageChannel.SMS))
                .extracting("code").isEqualTo("channel_unavailable");
        sender.requireChannel(MessageChannel.SMS_THEN_WHATSAPP);
        when(whatsApp.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> sender.requireChannel(MessageChannel.SMS_THEN_WHATSAPP))
                .extracting("code").isEqualTo("channel_unavailable");
        verify(ledger, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a secret-bearing kind never keeps its text")
    void secretsAreNotStored() {
        SupportMessage m = new SupportMessage(AGENT, SubjectKind.ORDER, UUID.randomUUID(), PHONE,
                RecipientRole.BUYER, SupportMessageKind.COLLECT_CODE, MessageChannel.SMS, "code 1234", Instant.now());
        assertThat(m.getBody()).isNull();
        m.complete(MessageOutcome.SENT, DeliveredVia.SMS, "code 1234", null, Instant.now());
        assertThat(m.getBody()).isNull();
    }
}
