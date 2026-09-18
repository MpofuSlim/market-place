package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.fulfilment.dto.CollectRequest;
import com.innbucks.marketplaceservice.fulfilment.dto.DispatchRequest;
import com.innbucks.marketplaceservice.fulfilment.dto.UnfulfillableRequest;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentPageResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentStatsResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The seller's fulfilment queue — MERCHANT_ADMIN on their own parcels,
 * SUPER_ADMIN on any.
 *
 * <p>The buyer's side of the same parcels lives on their order
 * ({@code GET /marketplace/orders/{id}} carries them, and
 * {@code POST /marketplace/orders/{id}/received} confirms receipt), because
 * that is where a shopper looks — they think in orders, not parcels.
 */
@Tag(name = "Fulfilment",
        description = "What happens to an order after it is paid. Work is tracked PER SELLER: a "
                + "cart spanning two sellers becomes two parcels, each moving on its own clock, "
                + "and the buyer's order rolls them up to the LEAST advanced one so \"delivered\" "
                + "always means everything arrived.\n\n"
                + "For a COLLECTION order read DISPATCHED as \"ready to collect\" and DELIVERED as "
                + "\"collected\" — the order's `deliveryMethod` says which wording applies.")
@RestController
@RequestMapping("/marketplace/fulfilments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
public class FulfilmentController {

    private final FulfilmentService fulfilmentService;
    private final SellerFulfilmentStatsService statsService;

    private static final String EXAMPLE_PARCEL = """
            {
              "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
              "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
              "orderRef": "MKT-4F9A1C22B7D3",
              "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
              "status": "PREPARING",
              "deliveryMethod": "DELIVERY",
              "destination": {
                "recipientName": "Tariro Moyo",
                "recipientMsisdn": "+263771234567",
                "line1": "14 Samora Machel Ave",
                "line2": "Flat 3B",
                "city": "Harare",
                "area": "Avondale",
                "landmark": "Opposite the clinic, blue gate"
              },
              "items": [
                {
                  "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                  "titleSnapshot": "Wireless Bluetooth Speaker",
                  "unitPriceCents": 2399,
                  "quantity": 2,
                  "lineTotalCents": 4798
                }
              ],
              "subtotalCents": 4798,
              "currency": "USD",
              "paidAt": "2026-09-14T11:20:10Z",
              "createdAt": "2026-09-14T11:20:10Z"
            }""";

    private static final String EXAMPLE_QUEUE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [""" + EXAMPLE_PARCEL + """
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String EXAMPLE_DISPATCHED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                "orderRef": "MKT-4F9A1C22B7D3",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "status": "DISPATCHED",
                "deliveryMethod": "DELIVERY",
                "destination": {
                  "recipientName": "Tariro Moyo",
                  "recipientMsisdn": "+263771234567",
                  "line1": "14 Samora Machel Ave",
                  "city": "Harare"
                },
                "items": [
                  {
                    "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "titleSnapshot": "Wireless Bluetooth Speaker",
                    "unitPriceCents": 2399,
                    "quantity": 2,
                    "lineTotalCents": 4798
                  }
                ],
                "subtotalCents": 4798,
                "currency": "USD",
                "paidAt": "2026-09-14T11:20:10Z",
                "dispatchNote": "Swift Couriers, waybill 88213",
                "dispatchedAt": "2026-09-15T09:20:00Z",
                "createdAt": "2026-09-14T11:20:10Z"
              }
            }""";

    private static final String EXAMPLE_ILLEGAL_409 = """
            {
              "code": "illegal_fulfilment_state",
              "message": "This parcel is DELIVERED and cannot move to DISPATCHED"
            }""";

    private static final String EXAMPLE_NOT_FOUND_404 = """
            {
              "code": "fulfilment_not_found",
              "message": "Fulfilment not found"
            }""";

    private static final String EXAMPLE_SCOPE_403 = """
            {
              "code": "merchant_scope_missing",
              "message": "Caller token carries no merchant scope"
            }""";

    @GetMapping
    @Operation(summary = "My fulfilment queue",
            description = "Parcels owed by the caller's merchant, OLDEST FIRST so the order that "
                    + "has waited longest never starves behind newer ones. A MERCHANT_ADMIN always "
                    + "sees only their own — `merchantId` is IGNORED for them, so a seller cannot "
                    + "widen their scope by sending a parameter; SUPER_ADMIN sees every merchant's "
                    + "and may narrow with it.\n\n"
                    + "Each row carries the DESTINATION and only THIS seller's lines and subtotal — "
                    + "never the whole order — so a seller in a multi-seller order learns nothing "
                    + "about what else the buyer bought.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of the queue",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_QUEUE_200))),
            @ApiResponse(responseCode = "403", description = "Not a seller or admin, or a merchant "
                    + "token with no merchant scope",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_SCOPE_403)))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentPageResponse>> queue(
            @Parameter(description = "Narrow to one state. Omitted returns every state — the "
                    + "seller's whole history, not just what is outstanding.")
            @RequestParam(required = false) FulfilmentStatus status,
            @Parameter(description = "SUPER_ADMIN only: narrow to one merchant. Ignored for a "
                    + "MERCHANT_ADMIN, who is always scoped to their own claim.")
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok(fulfilmentService.queue(
                CurrentUser.get(), status, merchantId, page, size)));
    }

    @GetMapping("/stats")
    @Operation(summary = "My fulfilment stats",
            description = "The trust figures shoppers see on the public seller profile — from the "
                    + "SAME computation, so a seller wondering \"why does my profile say 2 days?\" "
                    + "is looking at the very number the shopper sees — plus the live queue counts "
                    + "that are the seller's business alone (`awaitingDispatch` is the number to "
                    + "keep at zero).\n\n"
                    + "Every figure is COMPUTED from real parcels; small samples stay null rather "
                    + "than pretending two orders make a percentage. A MERCHANT_ADMIN always reads "
                    + "their own merchant — `merchantId` is ignored for them; SUPER_ADMIN must "
                    + "name one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's stats",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Success",
                              "data": {
                                "publicStats": {
                                  "completedOrders": 128,
                                  "medianDispatchHours": 20,
                                  "buyerConfirmedPercent": 96
                                },
                                "awaitingDispatch": 3,
                                "inTransit": 5,
                                "completedOrders": 128
                              }
                            }"""))),
            @ApiResponse(responseCode = "400", description = "SUPER_ADMIN without a merchantId",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "merchant_id_required",
                              "message": "merchantId is required when a SUPER_ADMIN reads a merchant's stats"
                            }"""))),
            @ApiResponse(responseCode = "403", description = "Merchant token with no merchant scope",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_SCOPE_403)))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentStatsResponse>> stats(
            @Parameter(description = "SUPER_ADMIN only: whose stats. Ignored for a MERCHANT_ADMIN.")
            @RequestParam(required = false) UUID merchantId) {
        return ResponseEntity.ok(ApiResult.ok(
                statsService.merchantStats(CurrentUser.get(), merchantId)));
    }

    @PostMapping("/{id}/dispatch")
    @Operation(summary = "Mark a parcel dispatched",
            description = "Sent (DELIVERY) or ready at the counter (COLLECTION). The optional "
                    + "`note` is the ONLY thing the buyer will see about how their goods are "
                    + "coming, so put the courier and waybill in it.\n\n"
                    + "Another seller's parcel is the same 404 as a nonexistent one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dispatched",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISPATCHED_200))),
            @ApiResponse(responseCode = "403", description = "Merchant token with no merchant scope",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_SCOPE_403))),
            @ApiResponse(responseCode = "404", description = "No such parcel for this seller",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "The parcel is already delivered",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_ILLEGAL_409)))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentResponse>> dispatch(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) DispatchRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Parcel dispatched",
                fulfilmentService.dispatch(CurrentUser.get(), id, request)));
    }

    @PostMapping("/{id}/delivered")
    @Operation(summary = "Mark a parcel delivered",
            description = "The seller's own close, available from either PREPARING (handed over in "
                    + "person, nothing was ever dispatched) or DISPATCHED. It exists because a "
                    + "buyer who never opens the app must not leave a parcel open forever — but "
                    + "the record keeps `deliveredBy: MERCHANT`, which is weaker evidence than a "
                    + "buyer's own confirmation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delivered",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Parcel marked delivered",
                              "data": {
                                "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                "orderRef": "MKT-4F9A1C22B7D3",
                                "status": "DELIVERED",
                                "deliveredAt": "2026-09-16T14:05:00Z",
                                "deliveredBy": "MERCHANT"
                              }
                            }"""))),
            @ApiResponse(responseCode = "404", description = "No such parcel for this seller",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "The parcel is already delivered",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_ILLEGAL_409)))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentResponse>> markDelivered(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok("Parcel marked delivered",
                fulfilmentService.markDelivered(CurrentUser.get(), id)));
    }

    @PostMapping("/{id}/unfulfillable")
    @Operation(summary = "Decline a parcel you cannot supply",
            description = "Ends a parcel you cannot send — out of stock, damaged, whatever "
                    + "happened — and puts things right in one step: the units go back on your "
                    + "shelf, the buyer's money is queued for refund, and the buyer is told why "
                    + "in your words.\n\n"
                    + "Available from PREPARING only. Once a parcel is DISPATCHED the goods are "
                    + "with a courier and this is no longer the truth; a delivery that then "
                    + "fails is the buyer's dispute to raise, because by then the two of you can "
                    + "disagree about what happened.\n\n"
                    + "Use it rather than leaving the parcel open. An open parcel holds the "
                    + "buyer's money indefinitely and reflects on your fulfilment stats exactly "
                    + "as badly as it sounds.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parcel closed, stock returned, "
                    + "refund queued where the money was still held",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Parcel closed - the buyer has been told",
                              "data": {
                                "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                "orderRef": "MKT-4F9A1C22B7D3",
                                "status": "UNFULFILLED",
                                "unfulfilledReason": "Out of stock - the last one was damaged in storage",
                                "unfulfilledAt": "2026-09-18T09:15:00Z",
                                "settlementStatus": "REFUND_DUE",
                                "settlementNetCents": 4798
                              }
                            }"""))),
            @ApiResponse(responseCode = "400", description = "No reason given",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"unfulfilled_reason_required","message":"Tell the buyer why - reason is required"}"""))),
            @ApiResponse(responseCode = "404", description = "No such parcel for this seller",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Already dispatched, delivered or "
                    + "declined — only a PREPARING parcel can be declined",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_ILLEGAL_409)))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentResponse>> unfulfillable(
            @PathVariable UUID id,
            @Valid @RequestBody UnfulfillableRequest request) {
        // The message deliberately does NOT promise a refund: money already
        // disputed, released or paid out turns around for nobody, and the
        // settlementStatus in the body is what says which happened.
        return ResponseEntity.ok(ApiResult.ok(
                "Parcel closed - the buyer has been told",
                fulfilmentService.markUnfulfillable(CurrentUser.get(), id, request)));
    }

    @PostMapping("/{id}/collect")
    @Operation(summary = "Redeem a collection code",
            description = "Hand the parcel over to whoever produced the code and close it. Scan "
                    + "the QR or type what they read out — dashes, spaces, case and the "
                    + "characters people confuse (I/L for 1, O for 0) are all handled.\n\n"
                    + "This closes the parcel as `deliveredBy: RECIPIENT`, which is the strongest "
                    + "evidence of handover the platform records — so **your money is released "
                    + "immediately** instead of waiting out the grace window a self-close starts. "
                    + "That is the reason to ask for the code rather than closing the parcel "
                    + "yourself.\n\n"
                    + "Only the buyer can create a code, and you never see one — you verify a "
                    + "code, you do not read one. Wrong codes are counted against the parcel and "
                    + "the tenth locks it; the buyer then mints a fresh one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collected",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Collected - the parcel is closed and your money is released",
                              "data": {
                                "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                "orderRef": "MKT-4F9A1C22B7D3",
                                "status": "DELIVERED",
                                "deliveredAt": "2026-09-16T15:40:00Z",
                                "deliveredBy": "RECIPIENT",
                                "collectorName": "Gogo Chipo Moyo",
                                "collectCodeIssued": false,
                                "collectCodeRedeemedAt": "2026-09-16T15:40:00Z",
                                "settlementStatus": "RELEASABLE",
                                "settlementNetCents": 4798
                              }
                            }"""))),
            @ApiResponse(responseCode = "404", description = "No such parcel for this seller",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Nothing to redeem here: a delivery "
                    + "order, no code issued yet, the parcel is already closed, or the wrong-code "
                    + "budget has run out",
                    content = @Content(examples = {
                            @ExampleObject(name = "No code yet", value = """
                                    {"code":"collect_code_unavailable","message":"No collection code has been issued for this parcel - ask the buyer to generate one in their app"}"""),
                            @ExampleObject(name = "Locked", value = """
                                    {"code":"collect_code_locked","message":"Too many wrong codes have been tried for this parcel - ask the buyer to generate a new one"}"""),
                            @ExampleObject(name = "Delivery order", value = """
                                    {"code":"collect_code_not_applicable","message":"This is a delivery order - there is nothing to collect in person"}""")})),
            @ApiResponse(responseCode = "422", description = "That code is not valid for this "
                    + "parcel — counted against the parcel's budget",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"collect_code_invalid","message":"That collection code is not valid for this parcel"}""")))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentResponse>> collect(
            @PathVariable UUID id,
            @Valid @RequestBody CollectRequest request) {
        return ResponseEntity.ok(ApiResult.ok(
                "Collected - the parcel is closed and your money is released",
                fulfilmentService.collect(CurrentUser.get(), id, request)));
    }
}
