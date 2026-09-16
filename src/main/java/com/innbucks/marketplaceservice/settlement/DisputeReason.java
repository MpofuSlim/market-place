package com.innbucks.marketplaceservice.settlement;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why the buyer disputed — a bounded vocabulary (the V7 report discipline):
 * the reason is what queues, meters and audits; free text rides {@code detail},
 * sanitized, and never becomes a category.
 */
@Schema(description = "Why the buyer is disputing this parcel")
public enum DisputeReason {
    /** Never arrived — the dispute that needs no delivered parcel. */
    NOT_RECEIVED,
    DAMAGED,
    NOT_AS_DESCRIBED,
    WRONG_ITEM,
    OTHER
}
