package com.innbucks.marketplaceservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ListingDeliveryTownRepository
        extends JpaRepository<ListingDeliveryTown, ListingDeliveryTown.Key> {

    List<ListingDeliveryTown> findByListingId(UUID listingId);

    /** A whole page (or basket) of listings' coverage in ONE query — the same
     *  batching discipline as the image gallery. */
    List<ListingDeliveryTown> findByListingIdIn(Collection<UUID> listingIds);

    /** Replacing a listing's coverage is delete-then-insert in one
     *  transaction; this is the delete half. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ListingDeliveryTown t WHERE t.listingId = :listingId")
    int deleteByListingId(@Param("listingId") UUID listingId);
}
