package com.innbucks.marketplaceservice.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A town a seller can deliver to (V14 {@code delivery_town}). Migration-seeded
 * and READ-ONLY at runtime, exactly like the category taxonomy: extend it with
 * a new migration, never through an API.
 *
 * <p>A controlled list rather than free text because delivery coverage is a
 * MATCH between two parties: the towns a seller covers and the town a buyer
 * lives in. Free text on both sides never meets ("Harare" vs "harare cbd").
 */
@Entity
@Table(name = "delivery_town")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryTown {

    /** Stable machine code, e.g. {@code victoria-falls}. What listings,
     *  addresses and orders store — never the display name. */
    @Id
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "province", nullable = false, length = 80)
    private String province;

    /** ISO country the town is in; each cell serves its own. */
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
