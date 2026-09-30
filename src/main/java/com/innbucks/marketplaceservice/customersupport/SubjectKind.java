package com.innbucks.marketplaceservice.customersupport;

/**
 * What a support note or activity row is about. Every marketplace subject is
 * identified by a UUID: a buyer by their {@code buyerUuid}, an order by its id,
 * a seller by their {@code merchantId} (the selling organization's id).
 */
public enum SubjectKind {
    BUYER,
    ORDER,
    SELLER
}
