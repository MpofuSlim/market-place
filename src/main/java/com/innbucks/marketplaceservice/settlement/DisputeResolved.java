package com.innbucks.marketplaceservice.settlement;

import java.util.UUID;

/**
 * In-process domain event: an operator resolved a dispute. Published inside
 * the resolving transaction; consumed AFTER_COMMIT by
 * {@link DisputeResolvedListener} to tell the buyer — so a rolled-back
 * resolution never announces itself (the fleet's ghost-side-effect rule).
 */
public record DisputeResolved(UUID disputeId,
                              UUID buyerUuid,
                              DisputeStatus outcome,
                              long netCents,
                              String currency,
                              String orderRef) {

    public static DisputeResolved of(SettlementDispute dispute, MerchantSettlement settlement,
                                     String orderRef) {
        return new DisputeResolved(dispute.getId(), dispute.getBuyerUuid(), dispute.getStatus(),
                settlement.getNetCents(), settlement.getCurrency(), orderRef);
    }
}
