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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Buyer order surface plus the SUPER_ADMIN oversight reads. Placing/cancelling
 * orders is CUSTOMER-only (an admin cannot move money on a buyer's behalf);
 * every CUSTOMER read/mutation is owner-scoped by the JWT subject — a
 * non-owner gets the same 404 as a nonexistent order, so the API never
 * confirms someone else's order ids. SUPER_ADMIN reads any order / all orders
 * (method-level {@code @PreAuthorize} per endpoint — there is deliberately no
 * class-level role gate anymore).
 */
@RestController
@RequestMapping("/marketplace/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Buyer orders end to end: create (reserves stock, mints a "
        + "payable total), read own, cancel while awaiting payment, track fulfilment and confirm "
        + "receipt. SUPER_ADMIN additionally reads all orders (GET /marketplace/orders, GET /{id}) "
        + "but can never place or cancel one.\n\n"
        + "**Paying is a different service.** Marketplace-service never collects money. While an "
        + "order is PENDING_PAYMENT its `payment` block names exactly what to send to the payments "
        + "service — `POST /payments` with `{orderType: \"MARKETPLACE\", orderRef, paymentRail}` "
        + "— and which rails this cell can actually collect on, so the app does not carry that "
        + "knowledge itself. That service confirms the order over the internal S2S surface; the "
        + "order then moves to PAID on its own.\n\n"
        + "**After payment** the order carries one fulfilment parcel per selling merchant, rolled "
        + "up into `fulfilmentStatus` as the LEAST advanced of them — so DELIVERED always means "
        + "everything arrived.")
public class OrderController {

    private final OrderService orderService;
    private final com.innbucks.marketplaceservice.settlement.DisputeService disputeService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Create an order",
            description = "Reserves stock atomically per line and mints a PENDING_PAYMENT order with a "
                    + "server-computed total (unit prices come from the listing rows — never from the "
                    + "client). The **Idempotency-Key header is required** and is namespaced per buyer: "
                    + "retrying with the same key replays the original response; reusing the key with a "
                    + "different body is refused. The order holds its stock until the payment TTL lapses "
                    + "(`expiresAt`), the buyer cancels, or the payments service confirms. "
                    + "**The payer is the caller:** when the token carries a `phoneNumber` claim (every "
                    + "CUSTOMER login does) it is used and `buyerMsisdn` is ignored; the body field is read "
                    + "only for a token without a phone. 400 `invalid_msisdn` when neither yields a number. "
                    + "\n\n**A refused order names EVERY failing line, not just the first.** When a listing "
                    + "has gone off sale or run short, the 422/409 body carries `data.rejections` with one "
                    + "entry per bad line (`reason`, a customer-safe `message`, `requestedQty`, and "
                    + "`availableQty`/`unitPriceCents` where they mean something) — so a cart with two "
                    + "problems is corrected once instead of over two round-trips. The top-level `code` and "
                    + "`message` still name the first offender exactly as before, so existing clients are "
                    + "unaffected. The order is refused as a whole either way: a partially-fulfilled order "
                    + "is never created.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CreateOrderRequest.class),
                            examples = {
                                    @ExampleObject(name = "Check out the cart", value = """
                                            {
                                              "fromCart": true,
                                              "deliveryMethod": "DELIVERY",
                                              "deliveryAddressId": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45"
                                            }
                                            """),
                                    @ExampleObject(name = "Buy Now, two lines, collected", value = """
                                            {
                                              "items": [
                                                { "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d", "quantity": 2 },
                                                { "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d", "quantity": 1 }
                                              ],
                                              "deliveryMethod": "COLLECTION"
                                            }
                                            """)})))
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
                                        "subtotalCents": 3550,
                                        "deliveryFeeCents": 200,
                                        "totalCents": 3750,
                                        "currency": "USD",
                                        "deliveryMethod": "DELIVERY",
                                        "deliveryAddress": {
                                          "recipientName": "Tariro Moyo",
                                          "recipientMsisdn": "+263771234567",
                                          "line1": "14 Samora Machel Ave",
                                          "line2": "Flat 3B",
                                          "city": "Harare",
                                          "area": "Avondale",
                                          "landmark": "Opposite the clinic, blue gate"
                                        },
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
                                        ],
                                        "payment": {
                                          "endpoint": "POST /payments",
                                          "orderType": "MARKETPLACE",
                                          "orderRef": "MKT-4F9A1C22B7D3",
                                          "amountCents": 3750,
                                          "currency": "USD",
                                          "payBefore": "2026-08-05T10:45:00Z",
                                          "methods": [
                                            {
                                              "rail": "INNBUCKS_CODE",
                                              "label": "InnBucks app",
                                              "description": "Approve the payment code in your InnBucks app.",
                                              "completion": "APPROVE_IN_APP"
                                            }
                                          ]
                                        },
                                        "fulfilments": []
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
                    + "still executing. A stock refusal carries `data.rejections` — EVERY short line, "
                    + "not just the first.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Insufficient stock (every short line listed)", value = """
                                    {
                                      "code": "insufficient_stock",
                                      "message": "Insufficient stock for listing 5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                      "data": {
                                        "rejections": [
                                          {
                                            "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                            "reason": "INSUFFICIENT_STOCK",
                                            "message": "Only 3 left of Solar Lantern 20W",
                                            "requestedQty": 5,
                                            "availableQty": 3,
                                            "unitPriceCents": 1550
                                          },
                                          {
                                            "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                            "reason": "INSUFFICIENT_STOCK",
                                            "message": "USB-C Charging Cable 2m is sold out",
                                            "requestedQty": 1,
                                            "availableQty": 0,
                                            "unitPriceCents": 450
                                          }
                                        ]
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Concurrent duplicate", value = """
                                    {"code":"request_in_flight","message":"A request with this Idempotency-Key is already in flight"}
                                    """)})),
            @ApiResponse(responseCode = "422", description = "Listing unavailable, or the key was "
                    + "reused with a different body. An unavailability refusal carries "
                    + "`data.rejections` — EVERY failing line, whatever the mix of reasons.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Listing unavailable (mixed reasons, all listed)", value = """
                                    {
                                      "code": "listing_unavailable",
                                      "message": "Listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d is not available",
                                      "data": {
                                        "rejections": [
                                          {
                                            "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                            "reason": "LISTING_UNAVAILABLE",
                                            "message": "Listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d is not available",
                                            "requestedQty": 2
                                          },
                                          {
                                            "listingId": "5e7a9b1c-3d2f-4a6b-8c9d-1e2f3a4b5c6d",
                                            "reason": "INSUFFICIENT_STOCK",
                                            "message": "Only 1 left of USB-C Charging Cable 2m",
                                            "requestedQty": 4,
                                            "availableQty": 1,
                                            "unitPriceCents": 450
                                          }
                                        ]
                                      }
                                    }
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
    @PreAuthorize("hasRole('CUSTOMER')")
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
                                            "subtotalCents": 3550,
                                            "deliveryFeeCents": 200,
                                            "totalCents": 3750,
                                            "currency": "USD",
                                            "deliveryMethod": "DELIVERY",
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

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List all orders (SUPER_ADMIN)",
            description = "Fleet-oversight read: every buyer's orders, newest first, optionally "
                    + "narrowed with ?buyerUuid=. Page size is hard-capped fleet-wide. SUPER_ADMIN "
                    + "only — CUSTOMER tokens are refused (buyers read their own via /mine).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Orders returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderPageResponse.class),
                            examples = @ExampleObject(name = "All orders", value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": {
                                        "items": [
                                          {
                                            "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                            "orderRef": "MKT-4F9A1C22B7D3",
                                            "status": "PENDING_PAYMENT",
                                            "subtotalCents": 3550,
                                            "deliveryFeeCents": 200,
                                            "totalCents": 3750,
                                            "currency": "USD",
                                            "deliveryMethod": "DELIVERY",
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
            @ApiResponse(responseCode = "400", description = "Malformed buyerUuid filter",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_buyer_uuid","message":"buyerUuid filter must be a UUID"}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}
                                    """))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}
                                    """)))
    })
    public ResponseEntity<ApiResult<OrderPageResponse>> getAll(
            @Parameter(description = "Optional filter: only this buyer's orders",
                    example = "6f9619ff-8b86-4011-b42d-00c04fc964ff",
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestParam(value = "buyerUuid", required = false) String buyerUuid,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResult.ok(OrderPageResponse.from(
                orderService.getAll(parseOptionalBuyerUuid(buyerUuid), pageable))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','SUPER_ADMIN')")
    @Operation(summary = "Get one order",
            description = "CUSTOMER: owner-scoped — an order that exists but belongs to someone else "
                    + "returns the same 404 as a nonexistent id. SUPER_ADMIN: reads ANY order by id "
                    + "(fleet oversight, no owner masking).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order returned",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class),
                            examples = @ExampleObject(name = "My order, paid and on its way", value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": {
                                        "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PAID",
                                        "subtotalCents": 3550,
                                        "deliveryFeeCents": 200,
                                        "totalCents": 3750,
                                        "currency": "USD",
                                        "deliveryMethod": "DELIVERY",
                                        "deliveryAddress": {
                                          "recipientName": "Tariro Moyo",
                                          "recipientMsisdn": "+263771234567",
                                          "line1": "14 Samora Machel Ave",
                                          "line2": "Flat 3B",
                                          "city": "Harare",
                                          "area": "Avondale",
                                          "landmark": "Opposite the clinic, blue gate"
                                        },
                                        "expiresAt": "2026-08-05T10:45:00Z",
                                        "createdAt": "2026-08-05T10:15:00Z",
                                        "paidAt": "2026-08-05T10:21:33Z",
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
                                        ],
                                        "fulfilmentStatus": "DISPATCHED",
                                        "fulfilments": [
                                          {
                                            "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                            "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                                            "sellerName": "Sunrise Electronics",
                                            "status": "DISPATCHED",
                                            "dispatchNote": "Swift Couriers, waybill 88213",
                                            "dispatchedAt": "2026-08-06T09:20:00Z",
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
            @ApiResponse(responseCode = "403", description = "Authenticated but neither CUSTOMER nor SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such order owned by the caller "
                    + "(SUPER_ADMIN: no such order at all)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"order_not_found","message":"Order not found"}
                                    """)))
    })
    public ResponseEntity<ApiResult<OrderResponse>> getOrder(
            @Parameter(description = "Order id (UUID)",
                    example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResult.ok(orderService.getOrder(CurrentUser.get(), parseOrderId(id))));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
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
                                        "subtotalCents": 3550,
                                        "deliveryFeeCents": 200,
                                        "totalCents": 3750,
                                        "currency": "USD",
                                        "deliveryMethod": "DELIVERY",
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

    @PostMapping("/{id}/fulfilments/{fulfilmentId}/received")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Confirm I received a parcel",
            description = "The last step of the journey. Closes ONE parcel — a multi-seller order "
                    + "is confirmed one seller at a time, because that is how the goods actually "
                    + "arrive.\n\n"
                    + "Lives on the order rather than the fulfilment resource because that is where "
                    + "a shopper looks: they think in orders, not parcels. Returns the whole order "
                    + "so the app re-renders its tracking screen from one response.\n\n"
                    + "The seller can also close a parcel themselves (`deliveredBy: MERCHANT`) — a "
                    + "buyer who never opens the app must not leave one open forever — but a "
                    + "buyer's own confirmation is the stronger record, and it is what this "
                    + "endpoint writes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Receipt confirmed; the whole order "
                    + "comes back with the parcel closed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "OK",
                                      "message": "Thanks - receipt confirmed",
                                      "data": {
                                        "id": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                        "orderRef": "MKT-4F9A1C22B7D3",
                                        "status": "PAID",
                                        "fulfilmentStatus": "DELIVERED",
                                        "fulfilments": [
                                          {
                                            "id": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                            "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                                            "sellerName": "Sunrise Electronics",
                                            "status": "DELIVERED",
                                            "deliveredAt": "2026-08-07T14:05:00Z",
                                            "deliveredBy": "BUYER"
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Malformed order or fulfilment id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_order_id","message":"Order id must be a UUID"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such order owned by the caller, or "
                    + "no such parcel on it",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"fulfilment_not_found","message":"Fulfilment not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "The parcel is already closed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"illegal_fulfilment_state","message":"This parcel is DELIVERED and cannot move to DELIVERED"}
                                    """)))
    })
    public ResponseEntity<ApiResult<OrderResponse>> confirmReceived(
            @Parameter(description = "Order id (UUID)",
                    example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
            @PathVariable("id") String id,
            @Parameter(description = "The parcel being confirmed, from the order's `fulfilments`",
                    example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
            @PathVariable("fulfilmentId") String fulfilmentId) {
        return ResponseEntity.ok(ApiResult.ok("Thanks - receipt confirmed",
                orderService.confirmReceived(CurrentUser.get(), parseOrderId(id),
                        parseId(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"))));
    }

    @PostMapping("/{id}/fulfilments/{fulfilmentId}/dispute")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Dispute a parcel",
            description = "The buyer's half of the escrow: freezes THIS parcel's money until an "
                    + "operator decides — the seller cannot be paid while it is open.\n\n"
                    + "Disputing an UNDELIVERED parcel is legal on purpose: \"it never arrived\" "
                    + "and \"the seller can't fulfil this\" are exactly the cases that need the "
                    + "money stopped, and this is also the platform's refund path. A DELIVERED "
                    + "parcel is disputable for a window after delivery (default 7 days). After "
                    + "the seller has actually been PAID, nothing is disputable — the money has "
                    + "left, and support takes it from there.\n\n"
                    + "**One dispute per parcel, ever.** The operator resolves it exactly once: "
                    + "released to the seller, or refunded to you.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dispute opened; the seller's money "
                    + "is frozen",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "OK",
                                      "message": "Dispute opened - we will review it and get back to you",
                                      "data": {
                                        "id": "5c8d1e2f-9a34-4b67-8c01-2d3e4f5a6b7c",
                                        "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                                        "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                        "reason": "NOT_RECEIVED",
                                        "detail": "Paid five days ago, the seller has stopped answering.",
                                        "status": "OPEN",
                                        "createdAt": "2026-09-15T10:00:00Z"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such order owned by the caller, "
                    + "or no such parcel on it",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"fulfilment_not_found","message":"Fulfilment not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Not disputable: already disputed, "
                    + "window closed, seller already paid, or already refunded",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Already disputed", value = """
                                            {"code":"dispute_already_raised","message":"This parcel has already been disputed"}
                                            """),
                                    @ExampleObject(name = "Window closed", value = """
                                            {"code":"dispute_window_closed","message":"This parcel was delivered more than 7 days ago and can no longer be disputed"}
                                            """)}))
    })
    public ResponseEntity<ApiResult<com.innbucks.marketplaceservice.settlement.dto.DisputeResponse>> dispute(
            @Parameter(description = "Order id (UUID)",
                    example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
            @PathVariable("id") String id,
            @Parameter(description = "The parcel being disputed, from the order's `fulfilments`")
            @PathVariable("fulfilmentId") String fulfilmentId,
            @Valid @RequestBody com.innbucks.marketplaceservice.settlement.dto.DisputeRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Dispute opened - we will review it and get back to you",
                disputeService.open(CurrentUser.get(), parseOrderId(id),
                        parseId(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"),
                        request.reason(), request.detail())));
    }

    /** GlobalExceptionHandler has no MethodArgumentTypeMismatch mapping, so a
     *  UUID-typed @PathVariable would 500 on garbage — parse here and 400. */
    private static UUID parseOrderId(String raw) {
        return parseId(raw, "invalid_order_id", "Order id must be a UUID");
    }

    private static UUID parseId(String raw, String code, String message) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(code, message);
        }
    }

    /** Absent/blank → null (no filter); present garbage is a clean 400 rather
     *  than silently returning every buyer's orders. */
    private static UUID parseOptionalBuyerUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_buyer_uuid", "buyerUuid filter must be a UUID");
        }
    }
}
