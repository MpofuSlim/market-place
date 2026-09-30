package com.innbucks.marketplaceservice.customersupport;

/**
 * The {@code support_activity.action} vocabulary. Strings, not an enum: the
 * column is read back by the supervisor feed, and a release that adds an
 * action must never make an older image unable to read the table (the same
 * reason notification types are strings fleet-wide).
 */
public final class SupportActions {

    public static final String SEARCH = "SEARCH";
    public static final String VIEW_BUYER = "VIEW_BUYER";
    public static final String VIEW_BUYER_ORDERS = "VIEW_BUYER_ORDERS";
    public static final String VIEW_ORDER = "VIEW_ORDER";
    public static final String VIEW_SELLER = "VIEW_SELLER";
    public static final String VIEW_SELLER_PARCELS = "VIEW_SELLER_PARCELS";
    public static final String NOTE_ADDED = "NOTE_ADDED";

    private SupportActions() {
    }
}
