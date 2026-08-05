package com.innbucks.marketplaceservice.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Listing lifecycle. Only ACTIVE listings are visible in the public catalog and
 * eligible for atomic stock reservation ({@code ListingRepository.reserveStock}
 * guards on ACTIVE inside the same UPDATE, so a deactivated listing can never
 * be ordered). New listings start as DRAFT; ARCHIVED is the soft-delete resting
 * state — rows are never physically deleted.
 */
@Schema(description = "Listing lifecycle status")
public enum ListingStatus {
    DRAFT,
    ACTIVE,
    INACTIVE,
    ARCHIVED
}
