package com.innbucks.marketplaceservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
 *       makes the exclusion structural). There is deliberately NO finder that
 *       returns the entity: the public endpoints read bytes through
 *       {@link ListingImageVariantRepository}, which selects a stored
 *       rendition by key, and the original's bytes only when they are the
 *       answer (V25).</li>
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

    /** In-place primary replace (POST /{id}/image): new bytes, type and
     *  created_at on the SAME id, without loading the old bytes. Bulk, so it
     *  executes immediately and takes the row lock before the caller rewrites
     *  the image's renditions (V25) — and created_at is what tells a stored
     *  rendition it describes older bytes. */
    @Modifying
    @Query("update ListingImage i set i.imageBytes = :bytes, i.contentType = :contentType, "
            + "i.createdAt = :createdAt where i.id = :id")
    int replaceImage(@Param("id") UUID id, @Param("bytes") byte[] bytes,
                     @Param("contentType") String contentType, @Param("createdAt") Instant createdAt);

    /** Bulk delete (no entity load, no byte fetch). Executes immediately, so a
     *  follow-up promotion query sees the row gone. */
    @Modifying
    @Query("delete from ListingImage i where i.id = :id")
    int deleteImageRow(@Param("id") UUID id);
}
