package com.innbucks.marketplaceservice.catalog;

/**
 * Physical condition of a listed item (V4 {@code listing.condition} column,
 * CHECK-constrained to these names). Default is {@link #NEW} — both in the
 * DB ({@code DEFAULT 'NEW'}) and in the service layer when a request omits
 * the field.
 */
public enum ItemCondition {
    NEW,
    USED_LIKE_NEW,
    USED_GOOD,
    USED_FAIR
}
