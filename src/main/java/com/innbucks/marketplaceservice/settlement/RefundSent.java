package com.innbucks.marketplaceservice.settlement;

import java.util.UUID;

/**
 * In-process domain event: an operator recorded that a parcel's refund has
 * actually been SENT to the buyer (REFUND_DUE → REFUNDED). Published inside
 * the recording transaction and consumed {@code AFTER_COMMIT}.
 *
 * <p>Exists because a cancelled parcel's buyer is told a refund "is being
 * arranged" and then heard nothing when it arrived — the one message about
 * their money that settles the matter. Carries the ids and the amount; the
 * listener reads the buyer's number from the order itself.
 */
public record RefundSent(UUID orderId,
                         UUID settlementId,
                         long amountCents,
                         String currency,
                         String refundReference) {
}
