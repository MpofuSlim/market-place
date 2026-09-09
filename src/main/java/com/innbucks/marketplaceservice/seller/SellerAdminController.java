package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.security.CurrentUser;
import com.innbucks.marketplaceservice.seller.dto.SellerDecisionRequest;
import com.innbucks.marketplaceservice.seller.dto.SellerPageResponse;
import com.innbucks.marketplaceservice.seller.dto.SellerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * SUPER_ADMIN seller-trust queue (V8).
 *
 * <p>Class-level role gate, exactly like {@code ModerationController} — a user
 * JWT plus the role, NOT the internal S2S surface, so it stays behind the
 * gateway's ordinary {@code /marketplace/**} route rather than the edge-denied
 * {@code /marketplace/internal/**} one. No gateway change is needed.
 */
@Tag(name = "Sellers (SUPER_ADMIN)",
        description = "Seller trust records: list by status (default PENDING, oldest first — FIFO) "
                + "and approve / reject / suspend / reinstate. APPROVED is what earns the "
                + "buyer-facing verified badge; only REJECTED and SUSPENDED stop a seller "
                + "publishing (a PENDING seller may still trade, unbadged).")
@RestController
@RequestMapping("/marketplace/admin/sellers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SellerAdminController {

    private final SellerService sellerService;

    private static final String EXAMPLE_SELLER = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "status": "APPROVED",
                "displayName": "Rudo Traders",
                "verified": true,
                "canPublish": true,
                "decidedBy": "1f0e2d3c-4b5a-6978-8695-a4b3c2d1e0f9",
                "decisionNote": null,
                "createdAt": "2026-04-01T09:15:00Z",
                "decidedAt": "2026-09-09T10:15:00Z"
              }
            }""";

    private static final String EXAMPLE_QUEUE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "status": "PENDING",
                    "displayName": null,
                    "verified": false,
                    "canPublish": true,
                    "decidedBy": null,
                    "decisionNote": null,
                    "createdAt": "2026-04-01T09:15:00Z",
                    "decidedAt": null
                  }
                ],
                "page": 0,
                "size": 20,
                "totalElements": 37,
                "totalPages": 2
              }
            }""";

    private static final String EXAMPLE_NOTE_REQUIRED = """
            {
              "code": "note_required",
              "message": "A note is required when rejecting or suspending a seller"
            }""";

    private static final String EXAMPLE_NOT_FOUND = """
            {
              "code": "seller_not_found",
              "message": "No seller record for merchant 7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54"
            }""";

    @GetMapping
    @Operation(summary = "List seller trust records",
            description = "Oldest first (FIFO). Omit `status` to list every seller. Pre-V8 merchants "
                    + "were backfilled as PENDING — that backlog is the queue's initial content.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of sellers",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = EXAMPLE_QUEUE))))
    public ApiResult<SellerPageResponse> list(
            @Parameter(description = "Filter by standing; omit for all")
            @RequestParam(required = false) SellerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(sellerService.list(status, page, size));
    }

    @PutMapping("/{merchantId}/approve")
    @Operation(summary = "Approve a seller",
            description = "Marks the seller APPROVED — the only status that earns the buyer-facing "
                    + "verified badge. Optionally sets the trading name shown on it. Re-approving "
                    + "an APPROVED seller is allowed, so a name can be corrected.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seller approved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SELLER))),
            @ApiResponse(responseCode = "404", description = "No such seller record",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND))),
            @ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ApiResult<SellerResponse> approve(
            @PathVariable UUID merchantId,
            @Valid @RequestBody(required = false) SellerDecisionRequest body) {
        return ApiResult.ok(sellerService.approve(CurrentUser.get(), merchantId, body));
    }

    @PutMapping("/{merchantId}/reject")
    @Operation(summary = "Reject a seller",
            description = "Refuses the seller: they keep any DRAFT work but cannot publish. "
                    + "`note` is REQUIRED - a seller told only 'no' cannot fix anything.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seller rejected",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SELLER))),
            @ApiResponse(responseCode = "400", description = "Note missing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOTE_REQUIRED))),
            @ApiResponse(responseCode = "404", description = "No such seller record",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Seller is already REJECTED"),
            @ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ApiResult<SellerResponse> reject(
            @PathVariable UUID merchantId,
            @Valid @RequestBody SellerDecisionRequest body) {
        return ApiResult.ok(sellerService.reject(CurrentUser.get(), merchantId, body));
    }

    @PutMapping("/{merchantId}/suspend")
    @Operation(summary = "Suspend a seller and take their listings down",
            description = "Stops the seller publishing AND deactivates every one of their ACTIVE "
                    + "listings in the same transaction — a suspension that left goods on sale "
                    + "would mean nothing. `note` is REQUIRED. Reversible via reinstate.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seller suspended, live listings deactivated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SELLER))),
            @ApiResponse(responseCode = "400", description = "Note missing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOTE_REQUIRED))),
            @ApiResponse(responseCode = "404", description = "No such seller record",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Seller is already SUSPENDED"),
            @ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ApiResult<SellerResponse> suspend(
            @PathVariable UUID merchantId,
            @Valid @RequestBody SellerDecisionRequest body) {
        return ApiResult.ok(sellerService.suspend(CurrentUser.get(), merchantId, body));
    }

    @PutMapping("/{merchantId}/reinstate")
    @Operation(summary = "Reinstate a suspended or rejected seller",
            description = "Returns the seller to APPROVED. Deliberately does NOT re-publish the "
                    + "listings a suspension took down — the seller chooses what goes back on "
                    + "sale. Only a SUSPENDED or REJECTED seller can be reinstated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seller reinstated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SELLER))),
            @ApiResponse(responseCode = "404", description = "No such seller record",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Seller is not SUSPENDED or REJECTED",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "seller_not_suspended",
                                      "message": "Only a SUSPENDED or REJECTED seller can be reinstated (status=APPROVED)"
                                    }"""))),
            @ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ApiResult<SellerResponse> reinstate(
            @PathVariable UUID merchantId,
            @Valid @RequestBody(required = false) SellerDecisionRequest body) {
        return ApiResult.ok(sellerService.reinstate(CurrentUser.get(), merchantId, body));
    }
}
