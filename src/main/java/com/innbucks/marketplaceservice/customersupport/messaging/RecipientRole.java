package com.innbucks.marketplaceservice.customersupport.messaging;

/**
 * Whose number on the record a support message goes to. Every role names a
 * number the platform already holds — the order's payer, the gift recipient
 * the buyer named, the person the buyer said would receive the delivery.
 * There is no role for "a number the agent typed", by design.
 */
public enum RecipientRole {
    /** The buyer: the order's payer, or one of the numbers the buyer has paid from. */
    BUYER,
    /** The person an order was bought for (a gift). */
    GIFT_RECIPIENT,
    /** The person the buyer named to receive a delivery. */
    DELIVERY_RECIPIENT
}
