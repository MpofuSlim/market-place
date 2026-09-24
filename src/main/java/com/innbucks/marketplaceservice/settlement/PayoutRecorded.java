package com.innbucks.marketplaceservice.settlement;

import java.util.UUID;

/** An operator recorded a payout run to one merchant. Handled after commit. */
public record PayoutRecorded(UUID merchantId, String payoutReference, int parcels,
                             long netCents, String currency) {
}
