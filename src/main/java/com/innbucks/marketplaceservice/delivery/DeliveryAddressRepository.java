package com.innbucks.marketplaceservice.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryAddressRepository extends JpaRepository<DeliveryAddress, UUID> {

    /** Default first, then newest — the order the address picker renders. */
    List<DeliveryAddress> findByBuyerUuidOrderByDefaultAddressDescCreatedAtDesc(UUID buyerUuid);

    /** Owner scoping IN THE QUERY: someone else's address and a nonexistent one
     *  are the same 404, so this is no existence oracle. */
    Optional<DeliveryAddress> findByIdAndBuyerUuid(UUID id, UUID buyerUuid);

    Optional<DeliveryAddress> findByBuyerUuidAndDefaultAddressTrue(UUID buyerUuid);

    long countByBuyerUuid(UUID buyerUuid);

    /**
     * Demotes every current default of one buyer, as a bulk statement run
     * BEFORE the new default is marked. Ordered that way so the partial unique
     * index never sees two defaults mid-transaction — the same demote-then-mark
     * discipline the V3 listing gallery uses for its primary image.
     *
     * <p>{@code excludingId} keeps a re-default of the row that is already
     * default from touching it (and from bumping its {@code updated_at}).
     */
    @Modifying
    @Query("""
            update DeliveryAddress a
               set a.defaultAddress = false, a.updatedAt = :now
             where a.buyerUuid = :buyerUuid
               and a.defaultAddress = true
               and a.id <> :excludingId
            """)
    int demoteOtherDefaults(@Param("buyerUuid") UUID buyerUuid,
                            @Param("excludingId") UUID excludingId,
                            @Param("now") Instant now);
}
