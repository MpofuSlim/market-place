package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.order.dto.CreateOrderRequest;
import com.innbucks.marketplaceservice.order.dto.OrderPageResponse;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Buyer order surface. CUSTOMER-only; every read/mutation is owner-scoped by
 * the JWT subject — a non-owner gets the same 404 as a nonexistent order, so
 * the API never confirms someone else's order ids.
 */
@RestController
@RequestMapping("/marketplace/orders")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Buyer orders: create (reserves stock, mints a payable total), "
        + "read own, cancel while awaiting payment. Payment itself rides the platform payments "
        + "service via the internal S2S surface.")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Create an order",
            description = "Reserves stock atomically per line and mints a PENDING_PAYMENT order with a "
                    + "server-computed total (unit prices come from the listing rows — never from the "
                    + "client). The **Idempotency-Key header is required** and is namespaced per buyer: "
                    + "retrying with the same key replays the original response; reusing the key with a "
                    + "different body is refused. The order holds its stock until the payment TTL lapses "
                    + "(`expiresAt`), the buyer cancels, or the payments service confirms.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CreateOrderRequest.class),
                            examples = @ExampleObject(name = "Two-line order", value = """
                                    {
                                      "buyerMsisdn": "+263771234567",
                                      "items": [
                                        { "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d", "quantity": 2 },
                                        { "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d", "quantity": 1 }
                                      ]
                                    }
                                    """))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created; stock reserved",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class),
                            examples = @ExampleObject(name = "Created order", value = """
                                    {
                                      "code": "CREATED",
                                      "message": "Created",
                                      "data": {
                                        "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PENDING_PAYMENT",
                                        "totalCents": 3550,
                                        "currency": "USD",
                                        "expiresAt": "2026-08-05T10:45:00Z",
                                        "createdAt": "2026-08-05T10:15:00Z",
                                        "items": [
                                          {
                                            "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                            "titleSnapshot": "Solar Lantern 20W",
                                            "unitPriceCents": 1550,
                                            "quantity": 2,
                                            "lineTotalCents": 3100
                                          },
                                          {
                                            "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                            "titleSnapshot": "USB-C Charging Cable 2m",
                                            "unitPriceCents": 450,
                                            "quantity": 1,
                                            "lineTotalCents": 450
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key, invalid msisdn, "
                    + "or invalid lines",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Missing Idempotency-Key", value = """
                                    {"code":"idempotency_key_required","message":"Idempotency-Key header is required"}
                                    """),
                            @ExampleObject(name = "Invalid msisdn", value = """
                                    {"code":"invalid_msisdn","message":"buyerMsisdn is not a valid phone number"}
                                    """),
                            @ExampleObject(name = "Too many lines", value = """
                                    {"code":"invalid_items","message":"Order must contain between 1 and 20 line items"}
                                    """),
                            @ExampleObject(name = "Quantity out of range", value = """
                                    {"code":"invalid_quantity","message":"Quantity for listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d must be between 1 and 25"}
                                    """),
                            @ExampleObject(name = "Duplicate line", value = """
                                    {"code":"duplicate_listing","message":"Listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d appears more than once in the order"}
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}
                                    """))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Not enough stock, or the same key is "
                    + "still executing",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Insufficient stock", value = """
                                    {"code":"insufficient_stock","message":"Insufficient stock for listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d"}
                                    """),
                            @ExampleObject(name = "Concurrent duplicate", value = """
                                    {"code":"request_in_flight","message":"A request with this Idempotency-Key is already in flight"}
                                    """)})),
            @ApiResponse(responseCode = "422", description = "Listing unavailable, or the key was "
                    + "reused with a different body",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Listing unavailable", value = """
                                    {"code":"listing_unavailable","message":"Listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d is not available"}
                                    """),
                            @ExampleObject(name = "Key reused with different body", value = """
                                    {"code":"idempotency_key_reuse","message":"Idempotency-Key was already used with a different request body"}
                                    """)}))
    })
    public ResponseEntity<ApiResult<OrderResponse>> create(
            @Parameter(description = "Client-chosen idempotency key; retry the SAME request with the "
                    + "same key to replay the original response", required = true,
                    example = "b1946ac9-2f6e-4b5a-8f0e-order-attempt-1")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(CurrentUser.get(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(response));
    }

    @GetMapping("/mine")
    @Operation(summary = "List my orders",
            description = "The caller's orders, newest first. Page size is hard-capped fleet-wide.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Orders returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderPageResponse.class),
                            examples = @ExampleObject(name = "My orders", value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": {
                                        "items": [
                                          {
                                            "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                            "orderRef": "MKT-4F9A1C22B7D3",
                                            "status": "PENDING_PAYMENT",
                                            "totalCents": 3550,
                                            "currency": "USD",
                                            "expiresAt": "2026-08-05T10:45:00Z",
                                            "createdAt": "2026-08-05T10:15:00Z",
                                            "items": [
                                              {
                                                "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                                "titleSnapshot": "Solar Lantern 20W",
                                                "unitPriceCents": 1550,
                                                "quantity": 2,
                                                "lineTotalCents": 3100
                                              },
                                              {
                                                "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                                "titleSnapshot": "USB-C Charging Cable 2m",
                                                "unitPriceCents": 450,
                                                "quantity": 1,
                                                "lineTotalCents": 450
                                              }
                                            ]
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalItems": 1,
                                        "totalPages": 1
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}
                                    """))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}
                                    """)))
    })
    public ResponseEntity<ApiResult<OrderPageResponse>> getMine(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResult.ok(
                OrderPageResponse.from(orderService.getMine(CurrentUser.get(), pageable))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of my orders",
            description = "Owner-scoped: an order that exists but belongs to someone else returns the "
                    + "same 404 as a nonexistent id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class),
                            examples = @ExampleObject(name = "My order", value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": {
                                        "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PENDING_PAYMENT",
                                        "totalCents": 3550,
                                        "currency": "USD",
                                        "expiresAt": "2026-08-05T10:45:00Z",
                                        "createdAt": "2026-08-05T10:15:00Z",
                                        "items": [
                                          {
                                            "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                            "titleSnapshot": "Solar Lantern 20W",
                                            "unitPriceCents": 1550,
                                            "quantity": 2,
                                            "lineTotalCents": 3100
                                          },
                                          {
                                            "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                            "titleSnapshot": "USB-C Charging Cable 2m",
                                            "unitPriceCents": 450,
                                            "quantity": 1,
                                            "lineTotalCents": 450
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Malformed order id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_order_id","message":"Order id must be a UUID"}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such order owned by the caller",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_found","message":"Order not found"}
                                    """)))
    })
    public ResponseEntity<ApiResult<OrderResponse>> getOrder(
            @Parameter(description = "Order id (UUID)",
                    example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResult.ok(orderService.getOwn(CurrentUser.get(), parseOrderId(id))));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel one of my orders",
            description = "Only a PENDING_PAYMENT order can be cancelled (the state machine refuses "
                    + "everything else with 409); its reserved stock is returned exactly once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled; stock returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class),
                            examples = @ExampleObject(name = "Cancelled order", value = """
                                    {
                                      "code": "OK",
                                      "message": "Order cancelled",
                                      "data": {
                                        "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "CANCELLED",
                                        "totalCents": 3550,
                                        "currency": "USD",
                                        "expiresAt": "2026-08-05T10:45:00Z",
                                        "createdAt": "2026-08-05T10:15:00Z",
                                        "items": [
                                          {
                                            "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                            "titleSnapshot": "Solar Lantern 20W",
                                            "unitPriceCents": 1550,
                                            "quantity": 2,
                                            "lineTotalCents": 3100
                                          },
                                          {
                                            "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                            "titleSnapshot": "USB-C Charging Cable 2m",
                                            "unitPriceCents": 450,
                                            "quantity": 1,
                                            "lineTotalCents": 450
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Malformed order id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_order_id","message":"Order id must be a UUID"}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such order owned by the caller",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_found","message":"Order not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Order is no longer cancellable",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"illegal_order_state","message":"Order MKT-4F9A1C22B7D3 cannot move from PAID to CANCELLED"}
                                    """)))
    })
    public ResponseEntity<ApiResult<OrderResponse>> cancel(
            @Parameter(description = "Order id (UUID)",
                    example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResult.ok("Order cancelled",
                orderService.cancelOrder(CurrentUser.get(), parseOrderId(id))));
    }

    /** GlobalExceptionHandler has no MethodArgumentTypeMismatch mapping, so a
     *  UUID-typed @PathVariable would 500 on garbage — parse here and 400. */
    private static UUID parseOrderId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_order_id", "Order id must be a UUID");
        }
    }
}
