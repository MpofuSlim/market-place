package com.innbucks.marketplaceservice.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ListingRepository extends JpaRepository<Listing, UUID>,
        JpaSpecificationExecutor<Listing> {

    /**
     * Atomic stock reservation for the order flow: decrements only when enough
     * stock exists AND the listing is ACTIVE, all inside one UPDATE — the
     * returned count is the success signal (0 = insufficient stock or not
     * ACTIVE; the caller must check it). Never read-modify-write stock through
     * the entity. Caller owns the transaction.
     */
    @Modifying
    @Query("""
            update Listing l
               set l.stockQty = l.stockQty - :q
             where l.id = :id
               and l.stockQty >= :q
               and l.status = com.innbucks.marketplaceservice.catalog.ListingStatus.ACTIVE
            """)
    int reserveStock(@Param("id") UUID id, @Param("q") int q);

    /**
     * Returns reserved stock on cancel/expiry. No status guard: a reservation
     * released after the merchant deactivated the listing must still restock —
     * the units were really held. Exactly-once is the order package's job
     * ({@code market_order.stock_released} double-release guard).
     */
    @Modifying
    @Query("update Listing l set l.stockQty = l.stockQty + :q where l.id = :id")
    int restock(@Param("id") UUID id, @Param("q") int q);

    /**
     * Atomic review-aggregate maintenance (V5): applied in the SAME transaction
     * as the listing_review write it mirrors, as a bulk UPDATE — never
     * read-modify-write through the entity (the stock discipline). Create:
     * (+rating, +1); edit: (delta, 0); delete: (-rating, -1).
     */
    @Modifying
    @Query("""
            update Listing l
               set l.ratingSum = l.ratingSum + :sumDelta,
                   l.ratingCount = l.ratingCount + :countDelta
             where l.id = :id
            """)
    int adjustRatingAggregates(@Param("id") UUID id,
                               @Param("sumDelta") long sumDelta,
                               @Param("countDelta") int countDelta);

    /** Current stock only — used by the restock-alert foundation to detect a
     *  0 → &gt;0 transition around {@link #restock} without loading the entity. */
    @Query("select l.stockQty from Listing l where l.id = :id")
    Integer stockQtyOf(@Param("id") UUID id);

    long countByMerchantId(UUID merchantId);

    Page<Listing> findByMerchantId(UUID merchantId, Pageable pageable);

    Optional<Listing> findByIdAndStatus(UUID id, ListingStatus status);

    /*
     * Public catalog browse runs through JpaSpecificationExecutor.findAll
     * (CatalogService.browse) with predicates built CONDITIONALLY — a filter
     * that is absent contributes NO predicate and therefore NO bind.
     *
     * That conditional construction is load-bearing, not style: the earlier
     * single "(:q is null or lower(...) ...)" nullable-param query died on
     * real PostgreSQL with "function lower(bytea) does not exist" (an untyped
     * null bind is inferred as bytea) — found by SecuritySurfaceIT on the
     * first CI run, invisible to mocked-repo unit tests. Never bind a null in
     * a browse predicate; build the predicate only when the value exists.
     * CatalogServiceTest pins the branch structure; SecuritySurfaceIT's
     * anonymous no-filter browse still proves it against real SQL.
     */
}
