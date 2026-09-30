package com.innbucks.marketplaceservice.customersupport.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, UUID>,
        JpaSpecificationExecutor<SupportMessage> {

    long countByAgentUuidAndCreatedAtAfter(String agentUuid, Instant after);

    long countByRecipientMsisdnAndCreatedAtAfter(String recipientMsisdn, Instant after);

    Page<SupportMessage> findBySubjectKindAndSubjectId(String subjectKind, String subjectId, Pageable pageable);

    /**
     * A buyer's whole support conversation: messages about the buyer AND about
     * any of their orders — support reaches a buyer through an order far more
     * often than through their profile. Both binds are always non-null.
     */
    @Query(value = """
            SELECT m.* FROM support_message m
            WHERE (m.subject_kind = 'BUYER' AND m.subject_id = :buyerId)
               OR (m.subject_kind = 'ORDER' AND m.subject_id IN (
                       SELECT CAST(o.id AS VARCHAR) FROM market_order o WHERE o.buyer_uuid = :buyerUuid))
            ORDER BY m.created_at DESC, m.id
            """,
            countQuery = """
            SELECT count(*) FROM support_message m
            WHERE (m.subject_kind = 'BUYER' AND m.subject_id = :buyerId)
               OR (m.subject_kind = 'ORDER' AND m.subject_id IN (
                       SELECT CAST(o.id AS VARCHAR) FROM market_order o WHERE o.buyer_uuid = :buyerUuid))
            """,
            nativeQuery = true)
    Page<SupportMessage> findForBuyer(@Param("buyerUuid") UUID buyerUuid, @Param("buyerId") String buyerId,
                                      Pageable pageable);

    /**
     * Serialises every send that shares a key, for the rest of the calling
     * transaction: the rate-limit count and the PENDING insert that claims the
     * slot happen under it, so two concurrent sends cannot both see "4 of 5"
     * and both go. Transaction-scoped, so there is nothing to release.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer lockSendKey(@Param("key") String key);
}
