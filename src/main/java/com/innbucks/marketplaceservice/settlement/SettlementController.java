package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.security.CurrentUser;
import com.innbucks.marketplaceservice.settlement.dto.DisputePageResponse;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.settlement.dto.PayoutRequest;
import com.innbucks.marketplaceservice.settlement.dto.PayoutResult;
import com.innbucks.marketplaceservice.settlement.dto.RefundRequest;
import com.innbucks.marketplaceservice.settlement.dto.SettlementResponse;
import com.innbucks.marketplaceservice.settlement.dto.ResolveDisputeRequest;
import com.innbucks.marketplaceservice.settlement.dto.SettlementPageResponse;
import com.innbucks.marketplaceservice.settlement.dto.SettlementSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The escrow's seller and operator surfaces. The BUYER's half (raising a
 * dispute, seeing its state) lives on their order — they think in orders, not
 * settlements.
 */
@Tag(name = "Settlements",
        description = "The escrow ledger: where each parcel's money is. The platform collects at "
                + "payment; a seller's money is HELD until their parcel is DELIVERED — released "
                + "immediately on the buyer's own confirmation, or after a grace window when the "
                + "seller closed the parcel themselves — and a buyer's dispute freezes it until "
                + "an operator decides. Payouts and refunds are OPERATOR actions on the payment "
                + "rails, recorded here with their references; this service moves no money itself.")
@RestController
@RequestMapping("/marketplace/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementQueryService queryService;
    private final SettlementService settlementService;
    private final DisputeService disputeService;
    private final SettlementViewAssembler views;

    private static final String EXAMPLE_SETTLEMENT_PAGE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": "9d2f7a10-3b64-4c8e-a1f5-6e7b8c9d0a12",
                    "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                    "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "status": "RELEASABLE",
                    "grossCents": 5598,
                    "commissionCents": 0,
                    "netCents": 5598,
                    "deliveryFeeCents": 800,
                    "currency": "USD",
                    "releasedAt": "2026-09-16T14:05:00Z",
                    "createdAt": "2026-09-14T11:20:10Z",
                    "orderRef": "MKT-4F9A1C22B7D3",
                    "itemSummary": "2 x Wireless Bluetooth Speaker",
                    "closedBy": "BUYER_CONFIRMED",
                    "closedAt": "2026-09-16T14:05:00Z"
                  },
                  {
                    "id": "0b7e3c91-2d48-4f5a-9c6e-1a2b3c4d5e6f",
                    "orderId": "c1d2e3f4-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
                    "fulfilmentId": "6e5d4c3b-2a19-4f8e-b7d6-c5b4a3928170",
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "status": "REFUND_DUE",
                    "grossCents": 1550,
                    "commissionCents": 0,
                    "netCents": 1550,
                    "deliveryFeeCents": 0,
                    "currency": "USD",
                    "refundDueAt": "2026-09-18T09:15:00Z",
                    "createdAt": "2026-09-17T16:40:00Z",
                    "orderRef": "MKT-9B3E7D10A4C2",
                    "itemSummary": "1 x Solar Lantern 20W",
                    "closedBy": "CANNOT_SUPPLY",
                    "closedAt": "2026-09-18T09:15:00Z",
                    "refundReason": "Out of stock - the last one was damaged in storage"
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String EXAMPLE_SUMMARY_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "payoutDestinationConfigured": true,
                "totals": [
                  { "status": "HELD", "parcels": 3, "netCents": 12500 },
                  { "status": "RELEASABLE", "parcels": 12, "netCents": 185000 },
                  { "status": "PAID_OUT", "parcels": 113, "netCents": 1730000 }
                ],
                "nextClearingAt": "2026-09-29T14:05:00Z",
                "clearingNext7DaysCents": 8200,
                "lastPayout": {
                  "paidOutAt": "2026-09-30T10:00:00Z",
                  "netCents": 48500,
                  "currency": "USD",
                  "parcels": 9,
                  "payoutReference": "PAYOUT-2026-09-30-01"
                }
              }
            }""";

    private static final String EXAMPLE_DISPUTE = """
            {
              "id": "5c8d1e2f-9a34-4b67-8c01-2d3e4f5a6b7c",
              "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
              "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
              "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
              "reason": "NOT_RECEIVED",
              "detail": "Paid five days ago, the seller has stopped answering.",
              "status": "OPEN",
              "netCents": 4798,
              "currency": "USD",
              "createdAt": "2026-09-15T10:00:00Z"
            }""";

    private static final String EXAMPLE_SCOPE_403 = """
            {
              "code": "merchant_scope_missing",
              "message": "Caller token carries no merchant scope"
            }""";

    // ------------------------------------------------------------------
    // Seller money views
    // ------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "My settlements",
            description = "One row per parcel: what the platform holds, has cleared, has paid, or "
                    + "has refunded. Newest first. A MERCHANT_ADMIN always reads their own — "
                    + "`merchantId` is IGNORED for them; SUPER_ADMIN reads all and may narrow.\n\n"
                    + "Each row carries what a person needs to recognise it: the buyer's "
                    + "`orderRef`, an `itemSummary`, `closedBy` (how the parcel ended — which is "
                    + "why one row cleared at once and another waits out the dispute window), "
                    + "`refundReason` on refund rows and `dispute` where the buyer raised one.\n\n"
                    + "`from` / `to` are calendar days in this market (inclusive), matched on "
                    + "when the buyer paid. The same filters export as CSV at `/statement`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of settlements",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_SETTLEMENT_PAGE_200))),
            @ApiResponse(responseCode = "400", description = "'to' before 'from', or a value "
                    + "that is not a date / state",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"invalid_date_range","message":"'to' is before 'from'"}"""))),
            @ApiResponse(responseCode = "403", description = "Merchant token with no merchant scope",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_SCOPE_403)))
    })
    public ResponseEntity<ApiResult<SettlementPageResponse>> list(
            @Parameter(description = "Narrow to one escrow state")
            @RequestParam(required = false) SettlementStatus status,
            @Parameter(description = "First day, inclusive (yyyy-MM-dd, this market's calendar)",
                    example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @Parameter(description = "Last day, inclusive", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @Parameter(description = "SUPER_ADMIN only; ignored for a MERCHANT_ADMIN")
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(queryService.list(CurrentUser.get(),
                        new SettlementQueryService.EarningsQuery(status, from, to, merchantId,
                                page, size))));
    }

    @GetMapping(value = "/statement", produces = "text/csv")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "My statement (CSV)",
            description = "The same rows as `GET /marketplace/settlements`, same filters, oldest "
                    + "first, as a spreadsheet. Dates and times are this market's wall clock; the "
                    + "period is in the FILENAME (never a header row above the columns). Money is "
                    + "in minor units, like every other surface. Refused past 5000 rows — choose "
                    + "a shorter period.\n\n"
                    + "A MERCHANT_ADMIN always exports their own; SUPER_ADMIN must name a "
                    + "`merchantId`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSV attachment",
                    content = @Content(mediaType = "text/csv", examples = @ExampleObject(value =
                            "date,orderRef,items,status,closedBy,closedAt,grossCents,deliveryFeeCents,"
                                    + "commissionCents,netCents,currency,clearsAt,releasedAt,paidOutAt,"
                                    + "payoutReference,refundedAt,refundReference,refundReason,"
                                    + "disputeStatus,disputeReason\n"
                                    + "2026-09-14,MKT-4F9A1C22B7D3,2 x Wireless Bluetooth Speaker,PAID_OUT,"
                                    + "BUYER_CONFIRMED,2026-09-16T16:05:00+02:00,5598,800,0,5598,USD,,"
                                    + "2026-09-16T16:05:00+02:00,2026-09-30T12:00:00+02:00,PAYOUT-2026-09-30-01,"
                                    + ",,,,\n"))),
            @ApiResponse(responseCode = "400", description = "SUPER_ADMIN without a merchantId, or "
                    + "'to' before 'from'",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {"code":"merchant_id_required","message":"merchantId is required when a SUPER_ADMIN exports a merchant's statement"}"""))),
            @ApiResponse(responseCode = "422", description = "More rows than a statement carries",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {"code":"statement_too_large","message":"More than 5000 rows - choose a shorter period"}""")))
    })
    public ResponseEntity<String> statement(
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @Parameter(description = "SUPER_ADMIN only (required for them); ignored for a "
                    + "MERCHANT_ADMIN")
            @RequestParam(required = false) UUID merchantId) {
        SettlementQueryService.Csv csv = queryService.statementCsv(CurrentUser.get(), status,
                from, to, merchantId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.content());
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Where is my money",
            description = "Parcels and net totals grouped by escrow state, in one read — the "
                    + "seller wallet header. States with nothing in them are simply not listed.\n\n"
                    + "Also: `nextClearingAt` (when the soonest held money clears on its own), "
                    + "`clearingNext7DaysCents`, and `lastPayout` (date, amount, parcels and the "
                    + "reference to look for on the seller's bank statement). Held money still "
                    + "waiting on delivery has no clearing date yet, so it is in the HELD total "
                    + "but not in either clearing figure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's totals",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_SUMMARY_200))),
            @ApiResponse(responseCode = "400", description = "SUPER_ADMIN without a merchantId",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "merchant_id_required",
                              "message": "merchantId is required when a SUPER_ADMIN reads a merchant's summary"
                            }""")))
    })
    public ResponseEntity<ApiResult<SettlementSummaryResponse>> summary(
            @Parameter(description = "SUPER_ADMIN only; ignored for a MERCHANT_ADMIN")
            @RequestParam(required = false) UUID merchantId) {
        return ResponseEntity.ok(ApiResult.ok(queryService.summary(CurrentUser.get(), merchantId)));
    }

    // ------------------------------------------------------------------
    // Operator: dispute queue
    // ------------------------------------------------------------------

    @GetMapping("/disputes")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "The dispute queue",
            description = "OLDEST first (FIFO — the buyer who has waited longest is served "
                    + "first). Default shows OPEN.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "One page of disputes",
            content = @Content(examples = @ExampleObject(value = """
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "items": [""" + EXAMPLE_DISPUTE + """
                        ],
                        "page": 0, "size": 20, "totalItems": 1, "totalPages": 1
                      }
                    }"""))))
    public ResponseEntity<ApiResult<DisputePageResponse>> disputes(
            @RequestParam(required = false, defaultValue = "OPEN") DisputeStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok(
                DisputePageResponse.from(disputeService.queue(status, page, size))));
    }

    @PatchMapping("/disputes/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Resolve a dispute",
            description = "Exactly once, one of two ways. RELEASE: the seller was right — their "
                    + "money returns to RELEASABLE and rides the next payout run. REFUND: the "
                    + "buyer was right — the refund is RECORDED with the operator's reference and "
                    + "EXECUTED by the operator on the rails (they have no reversal API this "
                    + "service could call; a ledger that pretended to move the money would be "
                    + "lying). The buyer is notified either way, after commit, best-effort.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resolved",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Dispute resolved",
                              "data": {
                                "id": "5c8d1e2f-9a34-4b67-8c01-2d3e4f5a6b7c",
                                "status": "REFUNDED",
                                "resolutionNote": "Courier photo shows the parcel left at the wrong address.",
                                "netCents": 4798,
                                "currency": "USD",
                                "resolvedAt": "2026-09-16T09:00:00Z"
                              }
                            }"""))),
            @ApiResponse(responseCode = "404", description = "No such dispute",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"dispute_not_found","message":"Dispute not found"}"""))),
            @ApiResponse(responseCode = "409", description = "Already resolved — a decision is "
                    + "made exactly once",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"dispute_not_open","message":"This dispute was already resolved (REFUNDED)"}""")))
    })
    public ResponseEntity<ApiResult<DisputeResponse>> resolve(
            @PathVariable UUID id, @Valid @RequestBody ResolveDisputeRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Dispute resolved",
                disputeService.resolve(CurrentUser.get(), id, request)));
    }

    // ------------------------------------------------------------------
    // Operator: payout
    // ------------------------------------------------------------------

    @PostMapping("/pay-out")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Record a payout run",
            description = "Marks EVERY RELEASABLE settlement of one merchant PAID_OUT under one "
                    + "payout reference — the shape finance pays in: one transfer per merchant "
                    + "covering everything cleared. Call AFTER making the transfer; the reference "
                    + "is what a seller asking \"where is my money?\" is answered with. Refuses an "
                    + "empty run (409 `nothing_releasable`) — a bank reference over no money would "
                    + "be a ledger entry describing nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The run's coverage",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Payout recorded",
                              "data": {
                                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                                "parcels": 12,
                                "totalNetCents": 185000,
                                "currency": "USD",
                                "payoutReference": "PAYOUT-2026-09-30-01"
                              }
                            }"""))),
            @ApiResponse(responseCode = "409", description = "Nothing releasable for this merchant",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"nothing_releasable","message":"This merchant has no releasable settlements to pay out"}""")))
    })
    public ResponseEntity<ApiResult<PayoutResult>> payOut(@Valid @RequestBody PayoutRequest request) {
        SettlementService.PayoutOutcome outcome = settlementService.payOutReleasable(
                CurrentUser.get(), request.merchantId(), request.payoutReference().trim());
        return ResponseEntity.ok(ApiResult.ok("Payout recorded", new PayoutResult(
                request.merchantId(), outcome.parcels(), outcome.totalNetCents(),
                outcome.currency(), request.payoutReference().trim())));
    }

    @GetMapping("/stale")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Money nobody is moving",
            description = "Settlements still HELD past the staleness threshold "
                    + "(`marketplace.settlement.stale-after-days`, default 14), oldest first.\n\n"
                    + "These are the rows no timer can reach: the release sweeper matches a "
                    + "`releasableAt` that only exists once a seller closes a parcel as "
                    + "delivered, so a parcel that was never delivered is invisible to it. Each "
                    + "row here is a buyer who paid, a seller who never delivered and never "
                    + "declined, and nobody watching.\n\n"
                    + "Nothing is decided for you, deliberately: chase the seller, or have them "
                    + "decline the parcel so the refund queues itself. The gauge "
                    + "`marketplace.settlements.stale` carries the same count for alerting.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Stale settlements, oldest first",
            content = @Content(examples = @ExampleObject(value = EXAMPLE_SETTLEMENT_PAGE_200))))
    public ResponseEntity<ApiResult<SettlementPageResponse>> stale(
            @Parameter(description = "How many to return (capped)")
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResult.ok(queryService.stale(size)));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Record a refund you have sent",
            description = "Closes one REFUND_DUE settlement as REFUNDED against your transfer "
                    + "reference. Call it AFTER making the transfer — this service moves no "
                    + "money, and REFUNDED means the money actually left.\n\n"
                    + "Per parcel rather than batched like the payout run, and that asymmetry is "
                    + "the shape of the money: a payout is one transfer to one merchant covering "
                    + "everything cleared, while a refund goes back to the individual buyer of "
                    + "one order. A single batched reference would be no proof to any one of "
                    + "them.\n\n"
                    + "A refund arising from a DISPUTE is recorded on the dispute instead "
                    + "(`PATCH /marketplace/settlements/disputes/{id}` with action REFUND) — the "
                    + "operator is already looking at it there.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refund recorded",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Refund recorded",
                              "data": {
                                "id": "9d2f7a10-3b64-4c8e-a1f5-6e7b8c9d0a12",
                                "status": "REFUNDED",
                                "netCents": 4798,
                                "currency": "USD",
                                "refundDueAt": "2026-09-18T09:15:00Z",
                                "refundedAt": "2026-09-18T11:02:00Z",
                                "refundReference": "RFND-2026-09-18-03"
                              }
                            }"""))),
            @ApiResponse(responseCode = "404", description = "No such settlement",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"settlement_not_found","message":"Settlement not found"}"""))),
            @ApiResponse(responseCode = "409", description = "This money is not owed back — only "
                    + "a REFUND_DUE settlement can be refunded this way. A DISPUTED row gets its "
                    + "own code: recording its refund here would leave the OPEN dispute "
                    + "unresolvable, so it is pointed at the dispute queue instead.",
                    content = @Content(examples = {
                            @ExampleObject(name = "not refund-due", value = """
                                    {"code":"illegal_settlement_state","message":"This settlement is HELD and cannot move to REFUNDED"}"""),
                            @ExampleObject(name = "disputed", value = """
                                    {"code":"settlement_disputed","message":"This settlement is DISPUTED - resolve the dispute (PATCH /marketplace/settlements/disputes/{id}, action REFUND) instead of refunding it directly"}""")
                    }))
    })
    public ResponseEntity<ApiResult<SettlementResponse>> refund(
            @PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Refund recorded",
                views.toResponse(settlementService.recordRefundPayment(
                        CurrentUser.get(), id, request.refundReference().trim()))));
    }

    @GetMapping(value = "/payout-report", produces = "text/csv")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "The payout report (CSV)",
            description = "Every merchant with RELEASABLE money, biggest owed first — the sheet "
                    + "finance pays from, one row per merchant with parcels, net total and "
                    + "trading name. The date rides the FILENAME, never a preamble row (a leading "
                    + "comment breaks every parser that treats line 1 as the header).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "CSV attachment",
            content = @Content(mediaType = "text/csv", examples = @ExampleObject(value =
                    "merchantId,displayName,parcels,netCents,currency\n"
                            + "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54,Sunrise Electronics,12,185000,USD\n"))))
    public ResponseEntity<String> payoutReport() {
        SettlementQueryService.Csv csv = queryService.payoutReportCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.content());
    }
}
