package com.innbucks.marketplaceservice.audit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * Access to the single {@code audit_chain_head} row (OWASP A09 hash-chaining).
 *
 * <p>{@link #lockHead()} issues a {@code SELECT ... FOR UPDATE} (pessimistic
 * write lock) on the head row so concurrent audit writes serialise their chain
 * appends — without this, two writers could read the same predecessor and fork
 * the chain, which would make a later deletion undetectable. Must be called
 * inside a transaction (it is — {@code AuditService} runs it under its
 * REQUIRES_NEW audit transaction).
 */
public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHead h where h.id = 1")
    Optional<AuditChainHead> lockHead();
}
