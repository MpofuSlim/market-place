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
 * An internal support note on a buyer, an order or a seller (V22). Append-only
 * by design and by trigger: there is no edit and no delete, because a case log
 * that can be rewritten afterwards proves nothing about what was known when.
 * A correction is a new note.
 */
@Entity
@Table(name = "support_note")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportNote implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "subject_kind", nullable = false, length = 20)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false, length = 80)
    private String subjectId;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "agent_uuid", nullable = false, length = 64)
    private String agentUuid;

    @Column(name = "agent_login")
    private String agentLogin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh;

    SupportNote(SubjectKind subjectKind, UUID subjectId, String body, SupportAgent agent, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.subjectKind = subjectKind.name();
        this.subjectId = subjectId.toString();
        this.body = body;
        this.agentUuid = agent.uuid();
        this.agentLogin = agent.login();
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
