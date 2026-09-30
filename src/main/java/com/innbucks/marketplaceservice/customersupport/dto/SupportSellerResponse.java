package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentStatsResponse;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.seller.PayoutMethod;
import com.innbucks.marketplaceservice.seller.SellerStatus;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.settlement.dto.SettlementSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "A marketplace seller as the call center sees them")
public record SupportSellerResponse(
        @Schema(example = "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d")
        UUID merchantId,

        @Schema(description = "Null when neither an operator nor the organization registry names them",
                example = "Rudo Traders")
        String displayName,

        @Schema(description = "Null for a seller who has listed but has no seller record yet")
        SellerStatus status,

        @Schema(description = "The operator's note on the last approval / rejection / suspension")
        String decisionNote,

        Instant decidedAt,

        @Schema(description = "When the seller record was created")
        Instant registeredAt,

        @Schema(description = "False for a delivery-only seller", example = "true")
        boolean collectionEnabled,

        Payout payout,

        @Schema(description = "The seller's live collection points")
        List<CollectionPointResponse> collectionPoints,

        @Schema(description = "Listings counted by status", example = "{\"DRAFT\":1,\"ACTIVE\":12,\"INACTIVE\":0,\"ARCHIVED\":3}")
        Map<ListingStatus, Long> listings,

        @Schema(description = "The seller's fulfilment stats and live queue counts, as their own portal shows them")
        MerchantFulfilmentStatsResponse fulfilment,

        @Schema(description = "The seller's money, as their own earnings summary shows it")
        SettlementSummaryResponse money,

        @Schema(description = "OPEN disputes against this seller, oldest first. At most 20.")
        List<DisputeResponse> openDisputes,

        @Schema(description = "OPEN reports against any of this seller's listings", example = "0")
        long openReports,

        SupportNotesSummary notes) {

    /**
     * Where the seller is paid — MASKED. The seller sees their own details in
     * full and finance reads them on the payout report; the call center only
     * needs to confirm a destination exists and roughly which it is. A support
     * screen that showed the full account would be the easiest place to learn
     * one — and redirecting a payout is THE attack on this data.
     */
    @Schema(description = "The payout destination, masked")
    public record Payout(
            @Schema(example = "true") boolean configured,
            PayoutMethod method,
            @Schema(description = "The name the account is held in", example = "R. Chikwanha") String accountName,
            @Schema(description = "Wallet number or account number, last 4 only", example = "****4521") String destination,
            @Schema(description = "BANK only", example = "CBZ Bank") String bankName,
            Instant updatedAt) {
    }
}
