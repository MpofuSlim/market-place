package com.innbucks.marketplaceservice.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single-row bookkeeping table that serialises appends to the {@code audit_events}
 * hash-chain (OWASP A09). There is exactly one row, {@code id = 1}, seeded by
 * the V1 migration.
 *
 * <p>On every audit write {@code AuditService} takes a {@code SELECT ... FOR
 * UPDATE} on this row (a pessimistic write lock) inside the audit transaction, so
 * concurrent writers append to the chain one at a time and can never fork it —
 * two rows building on the same predecessor would make deletion undetectable.
 * The row carries the last appended {@link #lastChainHmac} (the next write's
 * predecessor link) and {@link #lastEventId} for forensic cross-reference.
 */
@Entity
@Table(name = "audit_chain_head")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditChainHead {

    /** Always 1 — there is a single chain head for the whole table. Mapped as
     *  {@code Short} to match the SMALLINT column under ddl-auto: validate. */
    @Id
    @Column(name = "id")
    private Short id;

    /** chain_hmac of the most recently appended row; the next write's predecessor.
     *  Null before the first chained row is written (genesis). */
    @Column(name = "last_chain_hmac", length = 64)
    private String lastChainHmac;

    /** id of the most recently appended audit_events row (forensic reference). */
    @Column(name = "last_event_id")
    private Long lastEventId;
}
