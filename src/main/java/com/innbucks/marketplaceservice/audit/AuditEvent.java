package com.innbucks.marketplaceservice.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only row in the {@code audit_events} table (V1 baseline). Writes go
 * through {@code AuditService.record(...)} which manages a REQUIRES_NEW
 * transaction so audit failures don't break the caller's flow and audit
 * successes survive caller-side rollback.
 *
 * <p>Every field is non-functional from the application's point of view — the
 * rows exist purely for forensics, compliance reporting, and incident
 * response. The application never reads from this table on the hot path.
 */
@Entity
@Table(name = "audit_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "actor_uuid", length = 64)
    private String actorUuid;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * OWASP A09 tamper-evidence: HMAC-SHA256 over the immutable row fields
     * (see {@code AuditService.computeHmac}), keyed by
     * {@code marketplace.audit.hmac-secret}. Lets {@code AuditIntegrityVerifier}
     * detect any post-hoc modification of a row by an attacker with DB write
     * access. NOT NULL in this service — every row has been sealed since V1.
     */
    @Column(name = "row_hmac", nullable = false, length = 64)
    private String rowHmac;

    /**
     * OWASP A09 hash-chaining: {@code HMAC-SHA256(key, prev_chain_hmac || row_hmac)}
     * binding this row to its predecessor (see {@code AuditService.computeChainHmac}).
     * Where {@link #rowHmac} proves the row's content is intact, this proves no
     * row was DELETED, REORDERED, or truncated from the tail — deleting a row
     * breaks the link at the next surviving row, and the attacker can't repair
     * the downstream chain without the key. Nullable in the schema for fleet
     * uniformity (legacy rows elsewhere); this service chains every row.
     */
    @Column(name = "chain_hmac", length = 64)
    private String chainHmac;
}
