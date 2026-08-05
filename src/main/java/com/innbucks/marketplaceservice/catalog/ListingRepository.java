package com.innbucks.marketplaceservice.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ListingRepository extends JpaRepository<Listing, UUID> {

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

    long countByMerchantId(UUID merchantId);

    Page<Listing> findByMerchantId(UUID merchantId, Pageable pageable);

    Optional<Listing> findByIdAndStatus(UUID id, ListingStatus status);

    /*
     * Public catalog browse: ACTIVE only, optional case-insensitive title
     * 'contains' filter and exact category filter — one explicit query per
     * filter combination, selected in CatalogService.
     *
     * Deliberately NOT the single "(:q is null or ...)" nullable-param query:
     * on PostgreSQL an untyped null bind inside lower(...) is inferred as
     * bytea and the query dies at runtime with "function lower(bytea) does
     * not exist" (found by SecuritySurfaceIT on the first CI run — the
     * mocked-repo unit tests can't see it).
     *
     * {@code q} MUST already have its LIKE wildcards escaped with {@code !}
     * (see CatalogService.escapeLike) — a raw %/_ from a client would
     * otherwise act as a wildcard.
     */

    Page<Listing> findByStatus(ListingStatus status, Pageable pageable);

    Page<Listing> findByStatusAndCategory(ListingStatus status, String category, Pageable pageable);

    @Query("""
            select l from Listing l
             where l.status = :status
               and lower(l.title) like lower(concat('%', :q, '%')) escape '!'
            """)
    Page<Listing> findByStatusAndTitleLike(@Param("status") ListingStatus status,
                                           @Param("q") String q,
                                           Pageable pageable);

    @Query("""
            select l from Listing l
             where l.status = :status
               and l.category = :category
               and lower(l.title) like lower(concat('%', :q, '%')) escape '!'
            """)
    Page<Listing> findByStatusAndCategoryAndTitleLike(@Param("status") ListingStatus status,
                                                      @Param("category") String category,
                                                      @Param("q") String q,
                                                      Pageable pageable);
}
