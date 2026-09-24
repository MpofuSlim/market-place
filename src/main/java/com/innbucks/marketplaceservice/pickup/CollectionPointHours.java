package com.innbucks.marketplaceservice.pickup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One open interval of a collection point on one weekday (V18). Market-local
 * wall-clock times of day: never converted to UTC, because "08:00" at a
 * Harare counter means 08:00 in Harare whatever the server's clock says.
 */
@Entity
@Table(name = "seller_collection_point_hours")
@IdClass(CollectionPointHours.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectionPointHours {

    @Id
    @Column(name = "point_id", nullable = false)
    private UUID pointId;

    /** ISO day number, Monday = 1. */
    @Id
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Id
    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID pointId;
        private short dayOfWeek;
        private LocalTime opensAt;
    }
}
