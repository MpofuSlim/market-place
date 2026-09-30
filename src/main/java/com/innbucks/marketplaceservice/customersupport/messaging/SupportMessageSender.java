package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessageResponse;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessageComposer.Composed;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportRecipients.Recipient;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.MsisdnMasking;
import com.innbucks.marketplaceservice.notify.SmsNotificationClient;
import com.innbucks.marketplaceservice.notify.WhatsAppNotificationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends one support message: claim → gateway → record.
 *
 * <ol>
 *   <li>The caller has validated everything that can be refused (recipient,
 *       text, channel) — a refusal leaves no row.</li>
 *   <li>{@link SupportMessageLedger#claim} checks the limits and writes the
 *       PENDING row. The slot is spent from here, delivered or not.</li>
 *   <li>The gateway is called with NO transaction open.</li>
 *   <li>{@link SupportMessageLedger#complete} records the outcome and its
 *       activity row; the audit row follows in its own transaction.</li>
 * </ol>
 *
 * <p>Unlike every platform notification, a support send is SYNCHRONOUS and
 * reports what happened: the agent is on the phone with the customer and is
 * about to say "I've just sent you a message". A 502 with the record is the
 * honest answer when no channel took it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupportMessageSender {

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final SupportMessageLedger ledger;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;

    /**
     * @throws ApiException 503 {@code channel_unavailable} when no channel the
     *                      request allows is set up on this cell — before any
     *                      row is written, so an unusable channel costs no slot
     */
    public void requireChannel(MessageChannel channel) {
        boolean available = switch (channel) {
            case SMS -> sms.isConfigured();
            case WHATSAPP -> whatsApp.isConfigured();
            case SMS_THEN_WHATSAPP -> sms.isConfigured() || whatsApp.isConfigured();
        };
        if (!available) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "channel_unavailable", switch (channel) {
                case SMS -> "SMS is not set up on this deployment - try WhatsApp";
                case WHATSAPP -> "WhatsApp is not set up on this deployment - try SMS";
                case SMS_THEN_WHATSAPP -> "Neither SMS nor WhatsApp is set up on this deployment";
            });
        }
    }

    /** Claim, send and record a message whose text is known up front. */
    public SupportMessageResponse send(SupportAgent agent, Recipient recipient, SupportMessageKind kind,
                                       MessageChannel channel, Composed texts) {
        SupportMessage claimed = claim(agent, recipient, kind, channel, firstText(channel, texts));
        return dispatch(agent, claimed, channel, texts);
    }

    /** Spend the slot before the text exists — for a message whose text is a
     *  secret minted only once the send is known to be allowed. */
    public SupportMessage claim(SupportAgent agent, Recipient recipient, SupportMessageKind kind,
                                MessageChannel channel, String body) {
        return ledger.claim(agent, recipient, kind, channel, body);
    }

    /** Closes a claimed message that was never sent (its text could not be made). */
    public void abandon(SupportAgent agent, SupportMessage claimed, String failureCode) {
        SupportMessage done = ledger.complete(agent, claimed.getId(), MessageOutcome.FAILED, null, null,
                failureCode);
        audit(agent, done);
        metrics.notificationOutcome(metricType(done), "failed");
    }

    /**
     * Sends a claimed message and records the outcome.
     *
     * @throws ApiException 502 {@code message_not_delivered}, carrying the
     *                      FAILED record, when every channel refused it
     */
    public SupportMessageResponse dispatch(SupportAgent agent, SupportMessage claimed, MessageChannel channel,
                                           Composed texts) {
        Attempt attempt = deliver(claimed, channel, texts);
        SupportMessage done = ledger.complete(agent, claimed.getId(),
                attempt.via() == null ? MessageOutcome.FAILED : MessageOutcome.SENT, attempt.via(),
                attempt.via() == null ? null : texts.textFor(attempt.via()), attempt.failureCode());
        audit(agent, done);
        metrics.notificationOutcome(metricType(done), metricOutcome(channel, attempt.via()));
        SupportMessageResponse response = SupportMessageResponse.from(done);
        if (attempt.via() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "message_not_delivered",
                    "The message could not be delivered - neither channel accepted it", response);
        }
        return response;
    }

    private record Attempt(DeliveredVia via, String failureCode) {
    }

    private Attempt deliver(SupportMessage message, MessageChannel channel, Composed texts) {
        String to = message.getRecipientMsisdn();
        boolean smsFailed = false;
        if (channel.usesSms() && sms.isConfigured()) {
            try {
                // ~44 chars, inside the gateway's 46-char reference limit.
                sms.sendSms(to, texts.smsText(), "MKT-SUP-" + message.getId());
                return new Attempt(DeliveredVia.SMS, null);
            } catch (RuntimeException ex) {
                smsFailed = true;
                log.warn("Support {} SMS failed id={} to={}: {}", message.getKind(), message.getId(),
                        MsisdnMasking.mask(to), ex.getMessage());
            }
        }
        if (channel.usesWhatsApp() && whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(to, texts.whatsappText());
                return new Attempt(DeliveredVia.WHATSAPP, null);
            } catch (RuntimeException ex) {
                log.warn("Support {} WhatsApp failed id={} to={}: {}", message.getKind(), message.getId(),
                        MsisdnMasking.mask(to), ex.getMessage());
                return new Attempt(null, smsFailed ? "sms_and_whatsapp_failed" : "whatsapp_failed");
            }
        }
        // Only reachable if a channel was switched off between the check and
        // the send: say which one was tried, or that none could be.
        return new Attempt(null, smsFailed ? "sms_failed" : "channel_unavailable");
    }

    /** The text of the channel tried first — what the row holds until the
     *  channel that carried it is known. */
    private String firstText(MessageChannel channel, Composed texts) {
        return channel.usesSms() && sms.isConfigured() ? texts.smsText() : texts.whatsappText();
    }

    /** Evidence that support contacted a customer: who, what kind, which way,
     *  how it ended. Never the text and never the number. */
    private void audit(SupportAgent agent, SupportMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", message.getKind());
        metadata.put("subjectKind", message.getSubjectKind());
        metadata.put("subjectId", message.getSubjectId());
        metadata.put("recipientRole", message.getRecipientRole());
        metadata.put("channel", message.getChannelRequested());
        metadata.put("outcome", message.getOutcome());
        if (message.getDeliveredVia() != null) {
            metadata.put("deliveredVia", message.getDeliveredVia());
        }
        if (message.getFailureCode() != null) {
            metadata.put("failureCode", message.getFailureCode());
        }
        auditService.record(AuditEventType.SUPPORT_MESSAGE_SENT, agent.uuid(), message.getId().toString(),
                metadata);
    }

    private static String metricType(SupportMessage message) {
        return "support_" + message.getKind().toLowerCase(Locale.ROOT);
    }

    private static String metricOutcome(MessageChannel channel, DeliveredVia via) {
        if (via == null) {
            return "failed";
        }
        return via == DeliveredVia.WHATSAPP && channel == MessageChannel.SMS_THEN_WHATSAPP ? "fallback" : "sent";
    }
}
