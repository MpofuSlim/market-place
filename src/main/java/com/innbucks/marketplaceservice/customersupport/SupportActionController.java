package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.customersupport.dto.SupportDisputeRequest;
import com.innbucks.marketplaceservice.customersupport.dto.SupportOrderResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportReasonRequest;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The buyer's own actions, taken by support for a buyer who called in — on the
 * buyer's own rules, so the {@code actions} flags on the order the agent is
 * looking at already say which will succeed. Each needs a reason, kept as a
 * support note on the order, and answers with the order's support view as it
 * now stands.
 */
@Tag(name = "Customer support - actions",
        description = "Do for a buyer what they could do themselves: cancel an unpaid order or open a dispute "
                + "(`marketplace-support:manage`), or cancel a paid parcel before it ships, queueing the refund "
                + "(`marketplace-support:supervise`). The buyer's own rules apply - read `order.actions` and "
                + "`order.fulfilments[].actions` on the order view first. Deciding a dispute stays with the "
                + "operator queue.")
@RestController
@RequestMapping("/marketplace/support/orders/{orderId}")
@RequiredArgsConstructor
public class SupportActionController {

    static final String EXAMPLE_REASON_REQUEST = """
            { "reason": "Buyer called: ordered the wrong size, asked to cancel before it ships." }""";

    static final String EXAMPLE_DISPUTE_REQUEST = """
            {
              "reason": "NOT_RECEIVED",
              "detail": "Buyer called: paid six days ago, courier never came, seller not answering."
            }""";

    static final String EXAMPLE_REASON_400 = """
            {
              "code": "reason_required",
              "message": "Say why - the reason is kept as a note on the order"
            }""";

    static final String EXAMPLE_ORDER_STATE_409 = """
            {
              "code": "illegal_order_state",
              "message": "Order MKT-8B3E5D7F9A1C cannot move from PAID to CANCELLED"
            }""";

    static final String EXAMPLE_PARCEL_409 = """
            {
              "code": "parcel_not_cancellable",
              "message": "The seller has already sent this parcel - contact them, or open a dispute if it does not arrive"
            }""";

    static final String EXAMPLE_PARCEL_DISPUTED_409 = """
            {
              "code": "parcel_disputed",
              "message": "This parcel is under dispute - our support team will settle it"
            }""";

    static final String EXAMPLE_DISPUTE_409 = """
            {
              "code": "dispute_already_raised",
              "message": "This parcel has already been disputed"
            }""";

    static final String EXAMPLE_FULFILMENT_404 = """
            {
              "code": "fulfilment_not_found",
              "message": "Fulfilment not found"
            }""";

    private final SupportActionService actions;
    private final SupportOrderService orders;

    @PostMapping("/cancel")
    @PreAuthorize(SupportPermissions.CAN_MANAGE)
    @Operation(summary = "Cancel an UNPAID order for the buyer",
            description = "The buyer's own cancel: only while PENDING_PAYMENT (the order's `actions.canCancel`). "
                    + "Releases the stock. The reason is kept as a note on the order; the audit names you.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "reason", value = EXAMPLE_REASON_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled; the order as it now stands", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "order", value = SupportController.EXAMPLE_ORDER_200))),
            @ApiResponse(responseCode = "400", description = "No reason", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no reason", value = EXAMPLE_REASON_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = SupportController.EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:manage`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = SupportController.EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = SupportController.EXAMPLE_ORDER_404))),
            @ApiResponse(responseCode = "409", description = "Not unpaid any more", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "paid", value = EXAMPLE_ORDER_STATE_409)))
    })
    public ResponseEntity<ApiResult<SupportOrderResponse>> cancelOrder(@PathVariable UUID orderId,
                                                                       @Valid @RequestBody SupportReasonRequest request) {
        actions.cancelOrder(SupportAgent.of(CurrentUser.get()), orderId, request.reason());
        return ResponseEntity.ok(ApiResult.ok("Order cancelled", orders.afterAction(orderId)));
    }

    @PostMapping("/fulfilments/{fulfilmentId}/dispute")
    @PreAuthorize(SupportPermissions.CAN_MANAGE)
    @Operation(summary = "Open a dispute on a parcel for the buyer",
            description = "The buyer's own rule (the parcel's `actions.canDispute`): a paid order, money still "
                    + "arguable, inside the window for a delivered parcel, and one dispute per parcel ever. The "
                    + "seller's money freezes and the seller is alerted. `detail` goes to the operator who "
                    + "decides it and is kept as a note on the order. Deciding it is NOT here - it stays with "
                    + "the operator queue.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "never arrived", value = EXAMPLE_DISPUTE_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opened; the order as it now stands", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "order", value = SupportController.EXAMPLE_ORDER_200))),
            @ApiResponse(responseCode = "400", description = "No detail", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no detail", value = """
                            { "code": "detail_required", "message": "Say why - the detail is kept as a note on the order" }"""))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = SupportController.EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:manage`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = SupportController.EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order, or the parcel is not on it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown parcel", value = EXAMPLE_FULFILMENT_404))),
            @ApiResponse(responseCode = "409", description = "The buyer's rule says no", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "already disputed", value = EXAMPLE_DISPUTE_409)))
    })
    public ResponseEntity<ApiResult<SupportOrderResponse>> openDispute(@PathVariable UUID orderId,
                                                                       @PathVariable UUID fulfilmentId,
                                                                       @Valid @RequestBody SupportDisputeRequest request) {
        actions.openDispute(SupportAgent.of(CurrentUser.get()), orderId, fulfilmentId, request.reason(),
                request.detail());
        return ResponseEntity.ok(ApiResult.ok("Dispute opened", orders.afterAction(orderId)));
    }

    @PostMapping("/fulfilments/{fulfilmentId}/cancel")
    @PreAuthorize(SupportPermissions.CAN_SUPERVISE)
    @Operation(summary = "Cancel a PAID parcel before it ships, for the buyer (supervisors)",
            description = "The buyer's own cancel (the parcel's `actions.canCancel`): PREPARING, money still "
                    + "held, not disputed. The parcel ends, its stock goes back on sale and its money is queued "
                    + "for refund (REFUND_DUE) for the operator to pay; the seller is alerted. The reason is shown "
                    + "to the seller and kept as a note on the order. Needs `marketplace-support:supervise`.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "reason", value = EXAMPLE_REASON_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled; the order as it now stands", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "order", value = SupportController.EXAMPLE_ORDER_200))),
            @ApiResponse(responseCode = "400", description = "No reason", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no reason", value = EXAMPLE_REASON_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = SupportController.EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:supervise`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = SupportController.EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order, or the parcel is not on it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown parcel", value = EXAMPLE_FULFILMENT_404))),
            @ApiResponse(responseCode = "409", description = "The buyer's rule says no", content = @Content(
                    mediaType = "application/json", examples = {
                            @ExampleObject(name = "already sent", value = EXAMPLE_PARCEL_409),
                            @ExampleObject(name = "disputed", value = EXAMPLE_PARCEL_DISPUTED_409)}))
    })
    public ResponseEntity<ApiResult<SupportOrderResponse>> cancelParcel(@PathVariable UUID orderId,
                                                                        @PathVariable UUID fulfilmentId,
                                                                        @Valid @RequestBody SupportReasonRequest request) {
        AuthenticatedUser caller = CurrentUser.get();
        actions.cancelParcel(caller, orderId, fulfilmentId, request.reason());
        return ResponseEntity.ok(ApiResult.ok("Parcel cancelled", orders.afterAction(orderId)));
    }
}
