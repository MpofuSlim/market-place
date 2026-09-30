package com.innbucks.marketplaceservice.customersupport;

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
 * One row of the support activity log (V22): who looked at or did what, when.
 * Append-only — a database trigger refuses UPDATE and DELETE — so the entity
 * has no setters. {@link Persistable} so a save of a freshly built row is an
 * INSERT rather than a merge's SELECT-then-INSERT.
 */
@Entity
@Table(name = "support_activity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportActivity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "agent_uuid", nullable = false, length = 64)
    private String agentUuid;

    @Column(name = "agent_login")
    private String agentLogin;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "subject_kind", length = 20)
    private String subjectKind;

    @Column(name = "subject_id", length = 80)
    private String subjectId;

    /** Small JSON of ids and enums — never free text, never a raw phone. */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh;

    SupportActivity(SupportAgent agent, String action, String subjectKind, String subjectId,
                    String detail, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.agentUuid = agent.uuid();
        this.agentLogin = agent.login();
        this.action = action;
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.detail = detail;
        this.createdAt = createdAt;
        this.fresh = true;
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
