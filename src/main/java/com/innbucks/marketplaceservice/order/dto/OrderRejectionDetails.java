package com.innbucks.marketplaceservice.order.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The {@code data} payload on a refused order creation: every line that could
 * not be accepted, in the order they were sent.
 *
 * <p>Wrapped in an object rather than served as a bare array so the payload can
 * gain fields later without breaking a client that parses it — the same reason
 * every list response here is a paged object.
 */
@Schema(name = "OrderRejectionDetails",
        description = "Why an order could not be created — one entry per failing line.")
public record OrderRejectionDetails(

        @ArraySchema(arraySchema = @Schema(description = "Every line that failed, in request order. "
                + "Lines NOT listed here were acceptable; the order was still refused as a whole, "
                + "because a partially-fulfilled order is never created."),
                schema = @Schema(implementation = OrderLineRejection.class))
        List<OrderLineRejection> rejections
) {
    public static OrderRejectionDetails of(List<OrderLineRejection> rejections) {
        return new OrderRejectionDetails(List.copyOf(rejections));
    }
}
