package com.innbucks.marketplaceservice.pickup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CollectionPointHoursRepository
        extends JpaRepository<CollectionPointHours, CollectionPointHours.Key> {

    /** Batch: the hours of many points, one query, in weekly order. */
    @Query("""
            SELECT h FROM CollectionPointHours h
             WHERE h.pointId IN :pointIds
             ORDER BY h.pointId, h.dayOfWeek, h.opensAt""")
    List<CollectionPointHours> findForPoints(@Param("pointIds") Collection<UUID> pointIds);

    /** Hours are replaced whole with their point: delete, then insert the new set. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CollectionPointHours h WHERE h.pointId = :pointId")
    int deleteForPoint(@Param("pointId") UUID pointId);
}
