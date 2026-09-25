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
     * Atomic stock reservation for the order flow — a listing WITHOUT variants:
     * decrements only when enough stock exists AND the listing is ACTIVE AND it
     * has no variants, all inside one UPDATE. The returned count is the success
     * signal (0 = insufficient stock, not ACTIVE, or a variant listing; the
     * caller must check it). Native, because {@code stock_qty} is not updatable
     * through the entity (V19). Caller owns the transaction; only
     * {@code ListingStock} calls it.
     */
    @Modifying
    @Query(value = """
            UPDATE listing SET stock_qty = stock_qty - :q
             WHERE id = :id AND stock_qty >= :q AND status = 'ACTIVE' AND has_variants = FALSE
            """, nativeQuery = true)
    int reserveStock(@Param("id") UUID id, @Param("q") int q);

    /**
     * Returns reserved stock on cancel/expiry/decline — a listing WITHOUT
     * variants. No status guard: a reservation released after the merchant
     * deactivated the listing must still restock — the units were really held.
     * The {@code has_variants = FALSE} guard makes a return to a listing that
     * has since been converted to variants a 0 (metered, never credited to a
     * total it would no longer mean anything in). Exactly-once is the
     * callers' job ({@code market_order.stock_released},
     * {@code order_fulfilment.stock_returned}).
     */
    @Modifying
    @Query(value = """
            UPDATE listing SET stock_qty = stock_qty + :q
             WHERE id = :id AND has_variants = FALSE
            """, nativeQuery = true)
    int restock(@Param("id") UUID id, @Param("q") int q);

    /**
     * Locks one listing row for a stock movement and reads what the movement
     * needs to know first ({@code FOR NO KEY UPDATE} — the lock a non-key
     * UPDATE takes, so it never blocks the {@code FOR KEY SHARE} of an FK
     * insert into a child table). Null when there is no such row.
     *
     * <p>The lock rule (see {@code ListingStock}): a listing row before any of
     * its variant rows, and across listings in {@code java.util.UUID} order.
     * The aliases are quoted so the projection binds by exact name.
     */
    @Query(value = """
            SELECT status AS "status", has_variants AS "hasVariants", stock_qty AS "stockQty"
              FROM listing WHERE id = :id FOR NO KEY UPDATE
            """, nativeQuery = true)
    StockRow lockForStock(@Param("id") UUID id);

    /**
     * Sets a variant listing's derived total to the sum of its variants. Runs
     * ONLY while the transaction holds the listing row lock and AFTER that
     * transaction's variant statements — never a delta, so a drifted total
     * heals on the next movement and can never underflow the CHECK.
     * {@code flushAutomatically} so variant rows saved through the entity
     * manager are in the sum.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE listing SET stock_qty = (SELECT COALESCE(SUM(v.stock_qty), 0)
                                              FROM listing_variant v WHERE v.listing_id = :id)
             WHERE id = :id AND has_variants = TRUE
            """, nativeQuery = true)
    int recomputeStockTotal(@Param("id") UUID id);

    /** The seller's absolute stock set on a listing WITHOUT variants. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE listing SET stock_qty = :qty WHERE id = :id AND has_variants = FALSE",
            nativeQuery = true)
    int setPlainStock(@Param("id") UUID id, @Param("qty") int qty);

    /** True when the listing exists and sells options — the cart's question
     *  before it accepts a line with or without a variant. */
    boolean existsByIdAndHasVariantsTrue(UUID id);

    /**
     * Listings whose derived total disagrees with their variants, or whose
     * discriminator disagrees with whether variant rows exist. Only an
     * out-of-band write (an older image after a rollback, manual SQL) can make
     * one; {@code VariantStockDriftSweeper} reports the count and never
     * repairs.
     */
    @Query(value = """
            SELECT l.id FROM listing l
             WHERE (l.has_variants AND l.stock_qty <> COALESCE(
                        (SELECT SUM(v.stock_qty) FROM listing_variant v WHERE v.listing_id = l.id), 0))
                OR (l.has_variants AND NOT EXISTS
                        (SELECT 1 FROM listing_variant v WHERE v.listing_id = l.id))
                OR (NOT l.has_variants AND EXISTS
                        (SELECT 1 FROM listing_variant v WHERE v.listing_id = l.id))
             ORDER BY l.id
            """, nativeQuery = true)
    java.util.List<UUID> findStockDrift();

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

    /** Current stock (the derived total for a variant listing) — read by
     *  {@code ListingStock} UNDER the listing row lock, after a movement, to
     *  detect a 0 → &gt;0 transition without loading the entity. */
    @Query("select l.stockQty from Listing l where l.id = :id")
    Integer stockQtyOf(@Param("id") UUID id);

    /** The listing's seller, read WITHOUT a lock and without loading the
     *  entity — so an editor can refuse a listing that is not the caller's
     *  BEFORE taking its row lock (V19). Null when there is no such listing.
     *  Safe to check ahead of the lock because no path changes a listing's
     *  seller. */
    @Query("select l.merchantId from Listing l where l.id = :id")
    UUID merchantIdOf(@Param("id") UUID id);

    long countByMerchantId(UUID merchantId);

    /** How many of a merchant's listings a shopper can actually see — the
     *  figure the public seller profile reports. Deliberately NOT
     *  {@link #countByMerchantId}, which counts DRAFT and ARCHIVED rows the
     *  buyer surface hides. */
    long countByMerchantIdAndStatus(UUID merchantId, ListingStatus status);

    Page<Listing> findByMerchantId(UUID merchantId, Pageable pageable);

    Optional<Listing> findByIdAndStatus(UUID id, ListingStatus status);

    /**
     * Take every live listing of one merchant off sale, in ONE statement
     * (V8, used by {@code SellerService.suspend}).
     *
     * <p>A suspension that left goods on sale would mean nothing, and doing it
     * row-by-row would be an unbounded loop inside the admin's request for a
     * merchant with thousands of listings. INACTIVE rather than ARCHIVED: the
     * seller may be reinstated, and ARCHIVED is the soft-delete resting state.
     * Returns how many were taken down, which the audit record keeps.
     */
    @Modifying
    @Query("""
            update Listing l
               set l.status = com.innbucks.marketplaceservice.catalog.ListingStatus.INACTIVE,
                   l.updatedAt = :now
             where l.merchantId = :merchantId
               and l.status = com.innbucks.marketplaceservice.catalog.ListingStatus.ACTIVE
            """)
    int deactivateActiveListingsOf(@Param("merchantId") UUID merchantId,
                                   @Param("now") java.time.Instant now);

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
