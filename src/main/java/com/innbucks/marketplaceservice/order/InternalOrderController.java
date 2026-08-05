package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.order.dto.ConfirmPaymentRequest;
import com.innbucks.marketplaceservice.order.dto.InternalOrderView;
import com.innbucks.marketplaceservice.security.InternalTokenAuthorizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * S2S surface for the platform payments service, keyed by the opaque
 * {@code orderRef} — never the internal order id (the booking-service
 * contract shape). Excluded from the published OpenAPI spec
 * ({@code springdoc.paths-to-exclude}).
 *
 * <p>"Three files must agree" (fleet rule): every method here checks the
 * shared {@code X-Internal-Token} first ({@link InternalTokenAuthorizer},
 * constant-time); this repo's {@code SecurityConfig} permitAlls
 * {@code /marketplace/internal/**} so that check is reachable; and the fleet
 * gateway carries the {@code marketplace-internal-deny} edge route. A failed
 * token check answers 404 — not 401 — so a probe that somehow reaches this
 * far cannot even confirm the surface exists (the fleet webhook convention);
 * the authorizer counts the rejection metric and the probe is audited with
 * the path only, never the token.
 */
@Slf4j
@RestController
@RequestMapping("/marketplace/internal/orders")
@RequiredArgsConstructor
@Tag(name = "Orders (internal S2S)", description = "Payments-service surface: read an order by ref, "
        + "extend its stock hold, confirm payment. X-Internal-Token gated; edge-denied at the fleet "
        + "gateway; excluded from the published spec.")
public class InternalOrderController {

    private final OrderService orderService;
    private final InternalTokenAuthorizer internalTokenAuthorizer;
    private final AuditService auditService;

    @GetMapping("/{ref}")
    @SecurityRequirements()
    @Operation(summary = "Get order by ref (internal S2S)",
            description = "Resolves the amount to collect, currency, buyer contact and hold deadline "
                    + "before the payments service mints a payment code.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = InternalOrderView.class),
                            examples = @ExampleObject(name = "Order view", value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": {
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PENDING_PAYMENT",
                                        "totalCents": 3550,
                                        "currency": "USD",
                                        "buyerMsisdn": "+263771234567",
                                        "expiresAt": "2026-08-05T10:45:00Z"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Unknown ref — or missing/invalid "
                    + "X-Internal-Token (deliberately indistinguishable)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_found","message":"Order not found"}
                                    """)))
    })
    public ResponseEntity<ApiResult<InternalOrderView>> getByRef(
            @PathVariable String ref, HttpServletRequest request) {
        requireInternal(request);
        return ResponseEntity.ok(ApiResult.ok(orderService.getByRef(ref)));
    }

    @PatchMapping("/{ref}/extend-expiry")
    @SecurityRequirements()
    @Operation(summary = "Extend order expiry (internal S2S)",
            description = "Payments calls this before minting a payment code so the stock hold provably "
                    + "outlives the code. Never shortens the hold. Not a status change — journalled, "
                    + "but it does not go through the state machine.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Expiry extended (or already long enough)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = InternalOrderView.class),
                            examples = @ExampleObject(name = "Extended", value = """
                                    {
                                      "code": "OK",
                                      "message": "Order expiry extended",
                                      "data": {
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PENDING_PAYMENT",
                                        "totalCents": 3550,
                                        "currency": "USD",
                                        "buyerMsisdn": "+263771234567",
                                        "expiresAt": "2026-08-05T10:55:00Z"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "minutes missing or out of range",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_extension","message":"minutes must be between 1 and 60"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Unknown ref — or missing/invalid "
                    + "X-Internal-Token (deliberately indistinguishable)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_found","message":"Order not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Order is not PENDING_PAYMENT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_extendable","message":"Order MKT-4F9A1C22B7D3 is not awaiting payment"}
                                    """)))
    })
    public ResponseEntity<ApiResult<InternalOrderView>> extendExpiry(
            @PathVariable String ref,
            @Parameter(description = "Minutes from NOW the hold should last (1..60); never shortens",
                    example = "15")
            @RequestParam(value = "minutes", required = false) String minutes,
            HttpServletRequest request) {
        requireInternal(request);
        return ResponseEntity.ok(ApiResult.ok("Order expiry extended",
                orderService.extendExpiry(ref, parseMinutes(minutes))));
    }

    @PatchMapping("/{ref}/confirm-payment")
    @SecurityRequirements()
    @Operation(summary = "Confirm payment (internal S2S)",
            description = "Marks the order PAID after the payments service collected the money. "
                    + "Idempotent by paymentRef (a replay with the same ref is a 200 no-op). The amount "
                    + "is cross-checked against the order total — the 100x guard: a mismatch audits, "
                    + "counts, and parks the order (422); it NEVER confirms. CANCELLED/EXPIRED orders "
                    + "answer 409 and the payments service parks the money for the operator queue.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order PAID (or an idempotent replay of "
                    + "the same confirmation)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = InternalOrderView.class),
                            examples = @ExampleObject(name = "Confirmed", value = """
                                    {
                                      "code": "OK",
                                      "message": "Payment confirmed",
                                      "data": {
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PAID",
                                        "totalCents": 3550,
                                        "currency": "USD",
                                        "buyerMsisdn": "+263771234567",
                                        "expiresAt": "2026-08-05T10:45:00Z"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Unknown ref — or missing/invalid "
                    + "X-Internal-Token (deliberately indistinguishable)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_found","message":"Order not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Already paid under a different ref, or "
                    + "the order is CANCELLED/EXPIRED",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Different paymentRef", value = """
                                    {"code":"order_already_paid","message":"Order MKT-4F9A1C22B7D3 is already paid with a different payment reference"}
                                    """),
                            @ExampleObject(name = "Not confirmable", value = """
                                    {"code":"order_not_confirmable","message":"Order MKT-4F9A1C22B7D3 is EXPIRED and cannot be confirmed"}
                                    """)})),
            @ApiResponse(responseCode = "422", description = "Amount does not match the order total "
                    + "(the 100x guard) — audited and counted, order left unconfirmed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"amount_mismatch","message":"Paid amount 3500 does not match order total 3550"}
                                    """)))
    })
    public ResponseEntity<ApiResult<InternalOrderView>> confirmPayment(
            @PathVariable String ref,
            @Valid @RequestBody ConfirmPaymentRequest body,
            HttpServletRequest request) {
        requireInternal(request);
        return ResponseEntity.ok(ApiResult.ok("Payment confirmed",
                orderService.confirmPayment(ref, body)));
    }

    /**
     * Trust boundary for every method above. A rejected token answers the
     * SAME 404 as an unknown ref (no existence oracle — the fleet webhook
     * convention); the authorizer already counted
     * {@code marketplace.internal.token.rejected}, and the probe is audited
     * with the request path only — NEVER the presented token.
     */
    private void requireInternal(HttpServletRequest request) {
        if (!internalTokenAuthorizer.authorized(request)) {
            log.warn("Rejected X-Internal-Token on {} {}", request.getMethod(), request.getRequestURI());
            auditService.record(AuditEventType.INTERNAL_TOKEN_REJECTED, null, null,
                    Map.of("path", request.getRequestURI(), "method", request.getMethod()));
            throw ApiException.notFound("order_not_found", "Order not found");
        }
    }

    /** String param parsed by hand: GlobalExceptionHandler has no
     *  type-mismatch mapping, so an Integer @RequestParam would 500 on
     *  garbage — this answers the documented 400 instead. */
    private static Integer parseMinutes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null; // the service renders the range error
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("invalid_extension",
                    "minutes must be between " + OrderService.MIN_EXTEND_MINUTES + " and "
                            + OrderService.MAX_EXTEND_MINUTES);
        }
    }
}
