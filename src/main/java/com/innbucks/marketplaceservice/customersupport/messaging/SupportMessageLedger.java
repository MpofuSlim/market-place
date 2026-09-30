package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.SupportActions;
import com.innbucks.marketplaceservice.customersupport.SupportActivityLog;
import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportRecipients.Recipient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The two short transactions around a send. The gateway call between them runs
 * with NO transaction open: a pooled connection held across somebody else's
 * network call is how a slow SMS provider becomes a database outage.
 */
@Component
@RequiredArgsConstructor
public class SupportMessageLedger {

    static final Duration AGENT_WINDOW = Duration.ofHours(1);
    static final Duration RECIPIENT_WINDOW = Duration.ofHours(24);

    private final SupportMessageRepository repository;
    private final SupportMessagingProperties properties;
    private final SupportActivityLog activityLog;

    /**
     * Checks both limits and writes the PENDING row that claims the slot, under
     * per-agent and per-recipient locks so concurrent sends cannot both pass a
     * count that only one of them fits in. Locks are always taken agent first,
     * then recipient, so two sends can never wait on each other in a cycle.
     *
     * @throws ApiException 429 {@code support_message_rate_limited}
     */
    @Transactional
    public SupportMessage claim(SupportAgent agent, Recipient recipient, SupportMessageKind kind,
                                MessageChannel channel, String body) {
        repository.lockSendKey("support_message:agent:" + agent.uuid());
        repository.lockSendKey("support_message:recipient:" + recipient.msisdn());
        Instant now = Instant.now();
        if (repository.countByAgentUuidAndCreatedAtAfter(agent.uuid(), now.minus(AGENT_WINDOW))
                >= properties.getPerAgentPerHour()) {
            throw rateLimited("AGENT", properties.getPerAgentPerHour(), AGENT_WINDOW,
                    "You have sent " + properties.getPerAgentPerHour()
                            + " messages in the last hour - wait a little before sending more");
        }
        if (repository.countByRecipientMsisdnAndCreatedAtAfter(recipient.msisdn(), now.minus(RECIPIENT_WINDOW))
                >= properties.getPerRecipientPerDay()) {
            throw rateLimited("RECIPIENT", properties.getPerRecipientPerDay(), RECIPIENT_WINDOW,
                    "This customer has already had " + properties.getPerRecipientPerDay()
                            + " messages from support in the last 24 hours");
        }
        return repository.save(new SupportMessage(agent, recipient.subjectKind(), recipient.subjectId(),
                recipient.msisdn(), recipient.role(), kind, channel, body, now));
    }

    /**
     * Records how the send ended, with its activity row in the same
     * transaction. {@code sentBody} replaces the stored text when the channel
     * that carried the message sent a different form of it (null keeps it).
     */
    @Transactional
    public SupportMessage complete(SupportAgent agent, UUID messageId, MessageOutcome outcome, DeliveredVia via,
                                   String sentBody, String failureCode) {
        SupportMessage message = repository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("support message " + messageId + " vanished"));
        message.complete(outcome, via, sentBody, failureCode, Instant.now());
        repository.save(message);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("messageId", message.getId().toString());
        detail.put("kind", message.getKind());
        detail.put("channel", message.getChannelRequested());
        detail.put("recipientRole", message.getRecipientRole());
        detail.put("outcome", message.getOutcome());
        if (via != null) {
            detail.put("deliveredVia", via.name());
        }
        activityLog.record(agent, SupportActions.MESSAGE_SENT, SubjectKind.valueOf(message.getSubjectKind()),
                message.getSubjectId(), detail);
        return message;
    }

    private static ApiException rateLimited(String scope, int limit, Duration window, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope);
        data.put("limit", limit);
        data.put("windowMinutes", window.toMinutes());
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "support_message_rate_limited", message, data);
    }
}
