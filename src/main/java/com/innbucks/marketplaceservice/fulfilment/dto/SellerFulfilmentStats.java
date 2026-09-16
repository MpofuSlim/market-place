package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The PUBLIC trust figures about one seller, computed from their real
 * fulfilment history — never asserted, never defaulted.
 *
 * <p><b>Why this exists.</b> The app team asked for a response time on the
 * seller profile and were refused, correctly: the platform stored nothing it
 * could base one on, and a fabricated "responds within 24h" is a promise
 * nobody has any grounds to make. V9 changed the premise — every paid order
 * now leaves a parcel trail ({@code paid_at → dispatched_at → delivered_at},
 * and WHO closed it) — so the same figures can now be computed honestly.
 *
 * <p><b>Small samples stay silent.</b> Each figure is null until it rests on
 * at least {@code marketplace.seller-stats.min-sample} parcels: "100%
 * confirmed" over two orders is noise wearing a percentage, and a brand-new
 * seller deserves "new seller", not a damning-looking blank. The whole block
 * is absent for a seller with no completed parcel at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A seller's fulfilment track record, computed from real orders")
public record SellerFulfilmentStats(

        @Schema(description = "Parcels this seller has delivered, all time. From the seller's own "
                + "point of view this IS their completed order count — an order contributes one "
                + "parcel per seller.", example = "128")
        long completedOrders,

        @Schema(description = "Median hours from the buyer PAYING to this seller DISPATCHING, "
                + "rounded UP (the platform understates speed rather than overstating it; never "
                + "0 — a same-hour dispatch reads as 1). Null until enough parcels have been "
                + "dispatched to make a median meaningful, and for a seller who only hands goods "
                + "over in person (nothing is ever dispatched).",
                example = "20", nullable = true)
        Integer medianDispatchHours,

        @Schema(description = "Share of this seller's completed parcels that the BUYER confirmed "
                + "receiving, as an integer percent. A buyer's own confirmation is stronger "
                + "evidence than a seller marking their own parcel delivered — this is the "
                + "strength-of-evidence figure, not a score. Null below the minimum sample.",
                example = "96", nullable = true)
        Integer buyerConfirmedPercent) {
}
