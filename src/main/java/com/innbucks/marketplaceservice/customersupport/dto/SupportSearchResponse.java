package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.seller.SellerStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Everything a support search matched, grouped by kind. Each list holds at most 20.")
public record SupportSearchResponse(
        @Schema(description = "How the query was read: ORDER_REF, TRACKING_CODE, ID, PHONE or NAME",
                example = "PHONE")
        String queryKind,

        List<BuyerHit> buyers,

        List<OrderHit> orders,

        List<SellerHit> sellers) {

    @Schema(description = "A buyer the query matched")
    public record BuyerHit(
            @Schema(example = "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a")
            UUID buyerUuid,
            @Schema(description = "Orders the buyer has placed, any status", example = "4")
            long orders,
            @Schema(description = "Null for a buyer with no order yet (found by basket, address or favourite)")
            Instant lastOrderAt,
            @Schema(description = "Why this buyer matched: BUYER_PHONE, ORDER_BUYER, BUYER_ID",
                    example = "[\"BUYER_PHONE\"]")
            List<String> matchedAs) {
    }

    @Schema(description = "An order the query matched")
    public record OrderHit(
            @Schema(example = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d")
            UUID orderId,
            @Schema(example = "MKT-8B3E5D7F9A1C")
            String orderRef,
            OrderStatus status,
            @Schema(example = "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a")
            UUID buyerUuid,
            @Schema(example = "7897")
            long totalCents,
            @Schema(example = "USD")
            String currency,
            @Schema(description = "The order's summary method: DELIVERY if any seller delivers")
            DeliveryMethod deliveryMethod,
            Instant createdAt,
            @Schema(description = "Why this order matched: BUYER_PHONE, GIFT_RECIPIENT_PHONE, "
                    + "DELIVERY_RECIPIENT_PHONE, ORDER_REF, TRACKING_CODE, ORDER_ID, PARCEL_ID, RECIPIENT_NAME",
                    example = "[\"BUYER_PHONE\"]")
            List<String> matchedAs) {
    }

    @Schema(description = "A seller the query matched")
    public record SellerHit(
            @Schema(example = "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d")
            UUID merchantId,
            @Schema(description = "Null when neither an operator nor the organization registry names them",
                    example = "Rudo Traders")
            String displayName,
            @Schema(description = "Null for a seller who has listed but has no seller record yet")
            SellerStatus status,
            @Schema(description = "Why this seller matched: SELLER_ID, LISTING_ID, SELLER_NAME, PAYOUT_PHONE, "
                    + "ORDER_SELLER", example = "[\"ORDER_SELLER\"]")
            List<String> matchedAs) {
    }
}
