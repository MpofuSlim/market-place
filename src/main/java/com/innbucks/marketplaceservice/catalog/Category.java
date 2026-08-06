package com.innbucks.marketplaceservice.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Curated category taxonomy node (V4 {@code category} table): two levels —
 * top-level rows have {@code parentCode = null}, children reference their
 * parent's code. The taxonomy is migration-seeded and READ-ONLY at runtime
 * (no admin CRUD surface; extend it with a later migration), which is why
 * this entity has no version column and no write path in the service layer.
 */
@Entity
@Table(name = "category")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    /** Stable machine code, e.g. {@code phones-tablets}. This is the value
     *  listings store and browse filters match on — never the display name. */
    @Id
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** Null for top-level categories. */
    @Column(name = "parent_code", length = 40)
    private String parentCode;
}
