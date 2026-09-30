package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One message support sent a customer, or tried to (V23). Written PENDING
 * before the gateway is called and completed exactly once afterwards — the
 * database trigger refuses anything else, so what this row says was sent is
 * what was sent.
 */
@Entity
@Table(name = "support_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportMessage implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "subject_kind", nullable = false, length = 20)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false, length = 80)
    private String subjectId;

    @Column(name = "recipient_msisdn", nullable = false, length = 20)
    private String recipientMsisdn;

    @Column(name = "recipient_role", nullable = false, length = 30)
    private String recipientRole;

    @Column(name = "kind", nullable = false, length = 30)
    private String kind;

    @Column(name = "channel_requested", nullable = false, length = 20)
    private String channelRequested;

    @Column(name = "delivered_via", length = 20)
    private String deliveredVia;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "body")
    private String body;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "agent_uuid", nullable = false, length = 64)
    private String agentUuid;

    @Column(name = "agent_login")
    private String agentLogin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Transient
    private boolean fresh;

    SupportMessage(SupportAgent agent, SubjectKind subjectKind, UUID subjectId, String recipientMsisdn,
                   RecipientRole role, SupportMessageKind kind, MessageChannel channel, String body,
                   Instant createdAt) {
        this.id = UUID.randomUUID();
        this.subjectKind = subjectKind.name();
        this.subjectId = subjectId.toString();
        this.recipientMsisdn = recipientMsisdn;
        this.recipientRole = role.name();
        this.kind = kind.name();
        this.channelRequested = channel.name();
        this.outcome = MessageOutcome.PENDING.name();
        this.body = kind.secret() ? null : body;
        this.agentUuid = agent.uuid();
        this.agentLogin = agent.login();
        this.createdAt = createdAt;
        this.fresh = true;
    }

    /** Records how the send ended. The trigger allows this once. */
    void complete(MessageOutcome outcome, DeliveredVia via, String sentBody, String failureCode, Instant at) {
        this.outcome = outcome.name();
        this.deliveredVia = via == null ? null : via.name();
        if (!SupportMessageKind.valueOf(kind).secret() && sentBody != null) {
            this.body = sentBody;
        }
        this.failureCode = failureCode;
        this.completedAt = at;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
