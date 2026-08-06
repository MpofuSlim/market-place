package com.innbucks.marketplaceservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gallery access for {@link ListingImage}. Two disciplines, both load-bearing:
 *
 * <ul>
 *   <li><b>Metadata reads NEVER load bytes</b> — every list/response-assembly
 *       path uses the {@link ImageMeta} projection ({@code @Basic(LAZY)} on
 *       the bytes is only a hint without bytecode enhancement; the projection
 *       makes the exclusion structural). Full-entity finders exist ONLY for
 *       the two byte-serving endpoints.</li>
 *   <li><b>Primary swaps are ordered bulk statements</b> — {@code demotePrimary}
 *       THEN {@code markPrimary}, each flushed immediately in statement order,
 *       so the {@code uq_listing_image_primary} partial unique index never
 *       sees two primaries inside the transaction (entity-state updates flush
 *       in Hibernate's order, not call order — that bit event-service's
 *       banner code, don't regress to it).</li>
 * </ul>
 */
public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {

    /** Bytes-free view for response assembly and ownership/primary checks. */
    interface ImageMeta {
        UUID getId();

        UUID getListingId();

        String getContentType();

        boolean isPrimaryImage();

        int getPosition();
    }

    long countByListingId(UUID listingId);

    boolean existsByListingIdAndPrimaryImageTrue(UUID listingId);

    /** Gallery order: primary first, then append order. createdAt tiebreaks
     *  equal positions (possible across historical primary replacements). */
    List<ImageMeta> findByListingIdOrderByPrimaryImageDescPositionAscCreatedAtAsc(UUID listingId);

    /** One grouped query for a whole page of listings — the anti-N+1 read.
     *  Callers group by {@code getListingId()}; within a listing the order is
     *  the same as the single-listing finder. */
    List<ImageMeta> findByListingIdInOrderByPrimaryImageDescPositionAscCreatedAtAsc(Collection<UUID> listingIds);

    /** Metadata-only lookup scoped to the listing — the imageId must belong to
     *  that listing or this is empty (no cross-listing probing). */
    Optional<ImageMeta> findMetaByIdAndListingId(UUID id, UUID listingId);

    /** Metadata-only primary lookup — the back-compat delete-primary path. */
    Optional<ImageMeta> findMetaByListingIdAndPrimaryImageTrue(UUID listingId);

    /** FULL entity (bytes) — public per-image serving only. */
    Optional<ListingImage> findByIdAndListingId(UUID id, UUID listingId);

    /** FULL entity (bytes) — public primary-image serving and the in-place
     *  primary replacement (PUT /{id}/image) only. */
    Optional<ListingImage> findByListingIdAndPrimaryImageTrue(UUID listingId);

    /** Promotion candidate after a primary delete: lowest position wins. */
    Optional<ImageMeta> findFirstByListingIdOrderByPositionAscCreatedAtAsc(UUID listingId);

    @Query("select coalesce(max(i.position), -1) from ListingImage i where i.listingId = :listingId")
    int maxPosition(@Param("listingId") UUID listingId);

    @Modifying
    @Query("update ListingImage i set i.primaryImage = false where i.listingId = :listingId and i.primaryImage = true")
    int demotePrimary(@Param("listingId") UUID listingId);

    @Modifying
    @Query("update ListingImage i set i.primaryImage = true where i.id = :id")
    int markPrimary(@Param("id") UUID id);

    /** Bulk delete (no entity load, no byte fetch). Executes immediately, so a
     *  follow-up promotion query sees the row gone. */
    @Modifying
    @Query("delete from ListingImage i where i.id = :id")
    int deleteImageRow(@Param("id") UUID id);
}
