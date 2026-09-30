package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.customersupport.dto.SupportActivityPageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportBuyerResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNotePageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNoteRequest;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNoteResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportOrderResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportSearchRequest;
import com.innbucks.marketplaceservice.customersupport.dto.SupportSearchResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportSellerResponse;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentPageResponse;
import com.innbucks.marketplaceservice.order.dto.OrderPageResponse;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The call center's marketplace surface: find a buyer, an order or a seller,
 * see everything about them, and keep case notes.
 *
 * <p><b>Gated on PERMISSIONS, never roles</b> ({@link SupportPermissions}):
 * user-service grants {@code marketplace-support:*} to the call-center roles
 * (V45) and SUPER_ADMIN holds them through its wildcard. Every view of a
 * customer's record, and every search, is written to the support activity log
 * before it is shown.
 *
 * <p>Rides the plain {@code /marketplace/**} gateway route — an authenticated
 * staff surface, not an internal one.
 */
@Tag(name = "Customer support",
        description = "For the call center. Search by phone, order ref (MKT-...), tracking code (TRK-...), "
                + "id or name; open a buyer, an order or a seller; leave notes. Needs the "
                + "`marketplace-support:read` permission (notes: `marketplace-support:manage`; the activity "
                + "log: `marketplace-support:supervise`). Every search and view is logged.")
@RestController
@RequestMapping("/marketplace/support")
@RequiredArgsConstructor
public class SupportController {

    static final String AGENT_UUID = "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86";
    static final String BUYER_UUID = "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a";
    static final String ORDER_ID = "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d";
    static final String MERCHANT_ID = "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d";

    static final String EXAMPLE_SEARCH_REQUEST = """
            { "query": "0772 123 456" }""";

    static final String EXAMPLE_SEARCH_200 = """
            {
              "code": "OK",
              "message": "Search results",
              "data": {
                "queryKind": "PHONE",
                "buyers": [
                  { "buyerUuid": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a", "orders": 4,
                    "lastOrderAt": "2026-09-28T09:14:03Z", "matchedAs": ["BUYER_PHONE"] }
                ],
                "orders": [
                  { "orderId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d", "orderRef": "MKT-8B3E5D7F9A1C",
                    "status": "PAID", "buyerUuid": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a",
                    "totalCents": 7897, "currency": "USD", "deliveryMethod": "DELIVERY",
                    "createdAt": "2026-09-28T09:14:03Z", "matchedAs": ["BUYER_PHONE"] }
                ],
                "sellers": []
              }
            }""";

    static final String EXAMPLE_SEARCH_REF_200 = """
            {
              "code": "OK",
              "message": "Search results",
              "data": {
                "queryKind": "ORDER_REF",
                "buyers": [
                  { "buyerUuid": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a", "orders": 4,
                    "lastOrderAt": "2026-09-28T09:14:03Z", "matchedAs": ["ORDER_BUYER"] }
                ],
                "orders": [
                  { "orderId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d", "orderRef": "MKT-8B3E5D7F9A1C",
                    "status": "PAID", "buyerUuid": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a",
                    "totalCents": 7897, "currency": "USD", "deliveryMethod": "DELIVERY",
                    "createdAt": "2026-09-28T09:14:03Z", "matchedAs": ["ORDER_REF"] }
                ],
                "sellers": [
                  { "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d", "displayName": "Rudo Traders",
                    "status": "APPROVED", "matchedAs": ["ORDER_SELLER"] }
                ]
              }
            }""";

    static final String EXAMPLE_INVALID_SEARCH_400 = """
            {
              "code": "invalid_search",
              "message": "That is not a tracking code - they look like TRK-7F3K9Q2M4X"
            }""";

    static final String EXAMPLE_INVALID_MSISDN_400 = """
            {
              "code": "invalid_msisdn",
              "message": "query is not a valid phone number"
            }""";

    static final String EXAMPLE_BUYER_200 = """
            {
              "code": "OK",
              "message": "Buyer",
              "data": {
                "buyerUuid": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a",
                "phones": [ { "msisdn": "+263772123456", "orders": 4, "lastUsedAt": "2026-09-28T09:14:03Z" } ],
                "orders": {
                  "total": 4,
                  "lastOrderAt": "2026-09-28T09:14:03Z",
                  "byStatus": [
                    { "status": "CANCELLED", "orders": 1, "totalCents": 1999 },
                    { "status": "PAID", "orders": 3, "totalCents": 23691 }
                  ]
                },
                "recentOrders": [
                  { "id": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d", "orderRef": "MKT-8B3E5D7F9A1C",
                    "status": "PAID", "totalCents": 7897, "currency": "USD", "deliveryMethod": "DELIVERY",
                    "fulfilmentStatus": "DISPATCHED" }
                ],
                "openParcels": [
                  { "id": "6a2d9e41-3c7b-4f58-9e1a-7b4c2d8f0e35", "orderId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                    "orderRef": "MKT-8B3E5D7F9A1C", "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d",
                    "status": "DISPATCHED", "deliveryMethod": "DELIVERY", "trackingCode": "TRK-7F3K9Q2M4X",
                    "settlementStatus": "HELD", "settlementNetCents": 7897,
                    "buyerNotice": { "kind": "DISPATCHED", "outcome": "SMS", "at": "2026-09-29T08:02:11Z" } }
                ],
                "disputes": [],
                "refunds": [],
                "addresses": [
                  { "id": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45", "label": "Home", "recipientName": "Tariro Moyo",
                    "townCode": "harare", "defaultAddress": true }
                ],
                "engagement": { "basketLines": 2, "favourites": 5, "reviews": 1, "reportsFiled": 0 },
                "notes": {
                  "total": 1,
                  "latest": [
                    { "id": "7c1e4b92-8d3a-4f6e-b5a1-2c9d0e8f7a63", "subjectKind": "BUYER",
                      "subjectId": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a",
                      "body": "Asked for delivery after 5pm - passed to the seller.",
                      "createdBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                      "createdAt": "2026-09-29T10:30:00Z" }
                  ]
                }
              }
            }""";

    static final String EXAMPLE_BUYER_404 = """
            {
              "code": "buyer_not_found",
              "message": "No marketplace buyer with that id"
            }""";

    static final String EXAMPLE_ORDERS_200 = """
            {
              "code": "OK",
              "message": "Buyer orders",
              "data": {
                "items": [
                  { "id": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d", "orderRef": "MKT-8B3E5D7F9A1C",
                    "status": "PAID", "totalCents": 7897, "currency": "USD", "deliveryMethod": "DELIVERY",
                    "fulfilmentStatus": "DISPATCHED" }
                ],
                "page": 0, "size": 20, "totalItems": 4, "totalPages": 1
              }
            }""";

    static final String EXAMPLE_ORDER_200 = """
            {
              "code": "OK",
              "message": "Order",
              "data": {
                "order": {
                  "id": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d", "orderRef": "MKT-8B3E5D7F9A1C", "status": "PAID",
                  "subtotalCents": 7397, "deliveryFeeCents": 500, "totalCents": 7897, "currency": "USD",
                  "deliveryMethod": "DELIVERY", "fulfilmentStatus": "DISPATCHED"
                },
                "buyer": { "buyerUuid": "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a", "msisdn": "+263772123456" },
                "sellers": [ { "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d", "displayName": "Rudo Traders" } ],
                "parcels": [
                  { "id": "6a2d9e41-3c7b-4f58-9e1a-7b4c2d8f0e35", "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d",
                    "status": "DISPATCHED", "deliveryMethod": "DELIVERY", "trackingCode": "TRK-7F3K9Q2M4X",
                    "dispatchedAt": "2026-09-29T08:02:10Z", "settlementStatus": "HELD", "settlementNetCents": 7897,
                    "buyerNotice": { "kind": "DISPATCHED", "outcome": "SMS", "at": "2026-09-29T08:02:11Z" } }
                ],
                "settlements": [
                  { "id": "2d7b4e19-6c3a-4f8e-a1d5-9b0c3e7f2a64", "fulfilmentId": "6a2d9e41-3c7b-4f58-9e1a-7b4c2d8f0e35",
                    "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d", "status": "HELD", "grossCents": 7897,
                    "commissionCents": 0, "netCents": 7897, "deliveryFeeCents": 500, "currency": "USD" }
                ],
                "timeline": [
                  { "at": "2026-09-28T09:14:03Z", "kind": "PAYMENT", "fromStatus": null,
                    "toStatus": "PENDING_PAYMENT", "detail": "Order created" },
                  { "at": "2026-09-28T09:16:40Z", "kind": "PAYMENT", "fromStatus": "PENDING_PAYMENT",
                    "toStatus": "PAID", "detail": "Payment confirmed" },
                  { "at": "2026-09-29T08:02:10Z", "kind": "FULFILMENT", "fromStatus": "PREPARING",
                    "toStatus": "DISPATCHED", "detail": "Dispatched" }
                ],
                "notes": { "total": 0, "latest": [] }
              }
            }""";

    static final String EXAMPLE_ORDER_404 = """
            {
              "code": "order_not_found",
              "message": "Order not found"
            }""";

    static final String EXAMPLE_ORDER_KEY_400 = """
            {
              "code": "invalid_order_key",
              "message": "Name the order by its id or its reference (MKT-...)"
            }""";

    static final String EXAMPLE_SELLER_200 = """
            {
              "code": "OK",
              "message": "Seller",
              "data": {
                "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d",
                "displayName": "Rudo Traders",
                "status": "APPROVED",
                "decisionNote": null,
                "decidedAt": "2026-08-12T11:00:00Z",
                "registeredAt": "2026-08-10T14:22:09Z",
                "collectionEnabled": true,
                "payout": { "configured": true, "method": "MOBILE_MONEY", "accountName": "R. Chikwanha",
                            "destination": "****4521", "bankName": null, "updatedAt": "2026-08-12T12:03:00Z" },
                "collectionPoints": [
                  { "id": "c7a1e2b3-4d5f-4a6b-8c9d-0e1f2a3b4c5d", "name": "Avondale shop", "townCode": "harare",
                    "address": "12 King George Rd, Avondale", "isDefault": true,
                    "openingHoursSummary": "Mon-Fri 08:00-17:00, Sat 08:00-13:00" }
                ],
                "listings": { "DRAFT": 1, "ACTIVE": 12, "INACTIVE": 0, "ARCHIVED": 3 },
                "fulfilment": { "awaitingDispatch": 2, "inTransit": 1, "delivered": 41, "onTheWay": 1,
                                "readyToCollect": 0, "readyToCollectOverdue": 0, "collectionOverdueDays": 7 },
                "money": { "merchantId": "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d", "payoutDestinationConfigured": true,
                           "totals": [ { "status": "HELD", "parcels": 3, "netCents": 18450 },
                                       { "status": "PAID_OUT", "parcels": 38, "netCents": 402110 } ] },
                "openDisputes": [],
                "openReports": 0,
                "notes": { "total": 0, "latest": [] }
              }
            }""";

    static final String EXAMPLE_SELLER_404 = """
            {
              "code": "seller_not_found",
              "message": "No marketplace seller with that id"
            }""";

    static final String EXAMPLE_PARCELS_200 = """
            {
              "code": "OK",
              "message": "Seller parcels",
              "data": {
                "items": [
                  { "id": "6a2d9e41-3c7b-4f58-9e1a-7b4c2d8f0e35", "orderRef": "MKT-8B3E5D7F9A1C",
                    "status": "DISPATCHED", "deliveryMethod": "DELIVERY", "trackingCode": "TRK-7F3K9Q2M4X",
                    "settlementStatus": "HELD" }
                ],
                "page": 0, "size": 20, "totalItems": 1, "totalPages": 1
              }
            }""";

    static final String EXAMPLE_NOTE_REQUEST = """
            {
              "subjectKind": "ORDER",
              "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
              "body": "Buyer called: the courier left a missed-call. Asked the seller to re-attempt tomorrow."
            }""";

    static final String EXAMPLE_NOTE_201 = """
            {
              "code": "CREATED",
              "message": "Note added",
              "data": {
                "id": "7c1e4b92-8d3a-4f6e-b5a1-2c9d0e8f7a63",
                "subjectKind": "ORDER",
                "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                "body": "Buyer called: the courier left a missed-call. Asked the seller to re-attempt tomorrow.",
                "createdBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                "createdAt": "2026-09-30T08:41:12Z"
              }
            }""";

    static final String EXAMPLE_NOTES_200 = """
            {
              "code": "OK",
              "message": "Notes",
              "data": {
                "items": [
                  { "id": "7c1e4b92-8d3a-4f6e-b5a1-2c9d0e8f7a63", "subjectKind": "ORDER",
                    "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                    "body": "Buyer called: the courier left a missed-call. Asked the seller to re-attempt tomorrow.",
                    "createdBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                    "createdAt": "2026-09-30T08:41:12Z" }
                ],
                "page": 0, "size": 20, "totalItems": 1, "totalPages": 1
              }
            }""";

    static final String EXAMPLE_NOTE_REQUIRED_400 = """
            {
              "code": "note_required",
              "message": "Write something in the note"
            }""";

    static final String EXAMPLE_NOTE_VALIDATION_400 = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": { "body": "size must be between 0 and 2000" }
            }""";

    static final String EXAMPLE_ACTIVITY_200 = """
            {
              "code": "OK",
              "message": "Support activity",
              "data": {
                "items": [
                  { "id": "1b6e3d8a-2f4c-4a7e-9b5d-8c0e1f2a3b4c",
                    "agent": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                    "action": "NOTE_ADDED", "subjectKind": "ORDER", "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                    "detail": { "noteId": "7c1e4b92-8d3a-4f6e-b5a1-2c9d0e8f7a63" }, "createdAt": "2026-09-30T08:41:12Z" },
                  { "id": "5d9a2c7e-8b3f-4e1a-a6d4-0c2b7e9f1a38",
                    "agent": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                    "action": "VIEW_ORDER", "subjectKind": "ORDER", "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                    "detail": { "orderRef": "MKT-8B3E5D7F9A1C" }, "createdAt": "2026-09-30T08:39:55Z" },
                  { "id": "e4c1b8a2-7d6f-4b3e-9a5c-1f0d2e3b4a69",
                    "agent": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                    "action": "SEARCH", "subjectKind": null, "subjectId": null,
                    "detail": { "queryKind": "PHONE", "query": "****3456", "hits": 2 }, "createdAt": "2026-09-30T08:39:40Z" }
                ],
                "page": 0, "size": 50, "totalItems": 3, "totalPages": 1
              }
            }""";

    static final String EXAMPLE_401 = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing token",
              "data": null
            }""";

    static final String EXAMPLE_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role",
              "data": null
            }""";

    static final String EXAMPLE_BAD_PARAM_400 = """
            {
              "code": "invalid_parameter",
              "message": "'buyerUuid' has a value we cannot read"
            }""";

    private final SupportSearchService searchService;
    private final SupportBuyerService buyerService;
    private final SupportOrderService orderService;
    private final SupportSellerService sellerService;
    private final SupportNoteService noteService;
    private final SupportActivityLog activityLog;

    @PostMapping("/search")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "Search buyers, orders and sellers",
            description = "The query is read by its SHAPE: `MKT-...` is an order reference, `TRK-...` a "
                    + "tracking code, a UUID any id (buyer, order, parcel, seller or listing), anything "
                    + "dialable a phone number (the payer, a gift recipient or a delivery recipient, and a "
                    + "seller's payout wallet), anything else a name (a gift or delivery recipient, or a "
                    + "seller's operator-set name). Each list holds at most 20; `matchedAs` says why each "
                    + "row matched. POST so a phone number never lands in a URL. Every search is logged.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "by phone", value = EXAMPLE_SEARCH_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What matched (possibly nothing)",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "by phone", value = EXAMPLE_SEARCH_200),
                            @ExampleObject(name = "by order ref", value = EXAMPLE_SEARCH_REF_200)})),
            @ApiResponse(responseCode = "400", description = "A query that cannot be what it looks like",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bad tracking code", value = EXAMPLE_INVALID_SEARCH_400),
                            @ExampleObject(name = "not a phone number", value = EXAMPLE_INVALID_MSISDN_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403)))
    })
    public ResponseEntity<ApiResult<SupportSearchResponse>> search(@Valid @RequestBody SupportSearchRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Search results",
                searchService.search(agent(), request.query())));
    }

    @GetMapping("/buyers/{buyerUuid}")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "A buyer, on one screen",
            description = "Every number they have paid from (in full), their orders by status and the 5 "
                    + "newest exactly as the app shows them, open parcels as each seller sees them (tracking, "
                    + "collection-code state, the last notice and whether it reached the buyer, the money), "
                    + "disputes, refunds, saved addresses, basket/favourite/review counts and the newest notes. "
                    + "Logged as `VIEW_BUYER`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The buyer", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "buyer", value = EXAMPLE_BUYER_200))),
            @ApiResponse(responseCode = "400", description = "Not a UUID", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad id", value = EXAMPLE_BAD_PARAM_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No trace of this buyer here", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown buyer", value = EXAMPLE_BUYER_404)))
    })
    public ResponseEntity<ApiResult<SupportBuyerResponse>> buyer(@PathVariable UUID buyerUuid) {
        return ResponseEntity.ok(ApiResult.ok("Buyer", buyerService.profile(agent(), buyerUuid)));
    }

    @GetMapping("/buyers/{buyerUuid}/orders")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "A buyer's orders, newest first",
            description = "Every order the buyer has placed, any status, exactly as the app shows them. "
                    + "Page size at most 50. Logged as `VIEW_BUYER_ORDERS`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "orders", value = EXAMPLE_ORDERS_200))),
            @ApiResponse(responseCode = "400", description = "Not a UUID", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad id", value = EXAMPLE_BAD_PARAM_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No trace of this buyer here", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown buyer", value = EXAMPLE_BUYER_404)))
    })
    public ResponseEntity<ApiResult<OrderPageResponse>> buyerOrders(@PathVariable UUID buyerUuid,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok("Buyer orders", buyerService.orders(agent(), buyerUuid, page, size)));
    }

    @GetMapping("/orders/{orderKey}")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "One order: the buyer's view, every parcel, the money and the journal",
            description = "`orderKey` is the order id or its reference (`MKT-8B3E5D7F9A1C`; case and dashes "
                    + "forgiven). `order` is exactly what the buyer sees. `parcels` are each seller's card: "
                    + "tracking, collection-code state (issued, redeemed, locked, attempts left), the last "
                    + "buyer notice and whether it went by SMS, WhatsApp or FAILED, the settlement and any "
                    + "dispute. `timeline` is the order's journal, oldest first. Logged as `VIEW_ORDER`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "order", value = EXAMPLE_ORDER_200))),
            @ApiResponse(responseCode = "400", description = "Neither an id nor a reference", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad key", value = EXAMPLE_ORDER_KEY_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404)))
    })
    public ResponseEntity<ApiResult<SupportOrderResponse>> order(@PathVariable String orderKey) {
        return ResponseEntity.ok(ApiResult.ok("Order", orderService.detail(agent(), orderKey)));
    }

    @GetMapping("/sellers/{merchantId}")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "A seller, on one screen",
            description = "Status and decision, whether they collect, the payout destination MASKED (method, "
                    + "account name, last 4), collection points, listings by status, the same fulfilment stats "
                    + "and money summary their own portal shows, open disputes and reports, and the newest "
                    + "notes. Logged as `VIEW_SELLER`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The seller", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "seller", value = EXAMPLE_SELLER_200))),
            @ApiResponse(responseCode = "400", description = "Not a UUID", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad id", value = EXAMPLE_BAD_PARAM_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No trace of this seller here", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown seller", value = EXAMPLE_SELLER_404)))
    })
    public ResponseEntity<ApiResult<SupportSellerResponse>> seller(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(ApiResult.ok("Seller", sellerService.profile(agent(), merchantId)));
    }

    @GetMapping("/sellers/{merchantId}/parcels")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "A seller's parcel queue",
            description = "Exactly the seller's own queue (`GET /marketplace/fulfilments`), filtered the same "
                    + "way: `status`, `deliveryMethod` and `q` (order ref, tracking code, a phone number or a "
                    + "name). Oldest first. Logged as `VIEW_SELLER_PARCELS`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "parcels", value = EXAMPLE_PARCELS_200))),
            @ApiResponse(responseCode = "400", description = "A bad filter", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad id", value = EXAMPLE_BAD_PARAM_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No trace of this seller here", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown seller", value = EXAMPLE_SELLER_404)))
    })
    public ResponseEntity<ApiResult<MerchantFulfilmentPageResponse>> sellerParcels(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) FulfilmentStatus status,
            @RequestParam(required = false) DeliveryMethod deliveryMethod,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok("Seller parcels",
                sellerService.parcels(agent(), merchantId, status, deliveryMethod, q, page, size)));
    }

    @PostMapping("/notes")
    @PreAuthorize(SupportPermissions.CAN_MANAGE)
    @Operation(summary = "Add an internal note to a buyer, an order or a seller",
            description = "Append-only: notes cannot be edited or deleted (a correction is a new note). "
                    + "Plain text, 1-2000 characters; HTML is stripped. Never shown to the customer. Needs "
                    + "`marketplace-support:manage`.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "order note", value = EXAMPLE_NOTE_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Note added", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "added", value = EXAMPLE_NOTE_201))),
            @ApiResponse(responseCode = "400", description = "Empty or too long", content = @Content(
                    mediaType = "application/json", examples = {
                            @ExampleObject(name = "empty after stripping HTML", value = EXAMPLE_NOTE_REQUIRED_400),
                            @ExampleObject(name = "too long", value = EXAMPLE_NOTE_VALIDATION_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:manage`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "The subject does not exist", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404)))
    })
    public ResponseEntity<ApiResult<SupportNoteResponse>> addNote(@Valid @RequestBody SupportNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResult<>("CREATED", "Note added", noteService.add(agent(), request)));
    }

    @GetMapping("/notes")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "A subject's notes, newest first", description = "Page size at most 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "notes", value = EXAMPLE_NOTES_200))),
            @ApiResponse(responseCode = "400", description = "A bad subject", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad id", value = EXAMPLE_BAD_PARAM_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403)))
    })
    public ResponseEntity<ApiResult<SupportNotePageResponse>> notes(@RequestParam SubjectKind subjectKind,
                                                                    @RequestParam UUID subjectId,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok("Notes", noteService.list(subjectKind, subjectId, page, size)));
    }

    @GetMapping("/activity")
    @PreAuthorize(SupportPermissions.CAN_SUPERVISE)
    @Operation(summary = "The support activity log (supervisors)",
            description = "Every support search, view and action, newest first — who opened whose record and "
                    + "what they did. Filters: `agentUuid`, `action`, `subjectKind` + `subjectId`, and a "
                    + "`from`/`to` window (ISO-8601 instants; `to` exclusive). A phone number in `detail` is "
                    + "masked; free text never appears. Page size at most 100. Needs "
                    + "`marketplace-support:supervise`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "activity", value = EXAMPLE_ACTIVITY_200))),
            @ApiResponse(responseCode = "400", description = "A bad filter", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "bad value", value = EXAMPLE_BAD_PARAM_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:supervise`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403)))
    })
    public ResponseEntity<ApiResult<SupportActivityPageResponse>> activity(
            @RequestParam(required = false) String agentUuid,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) SubjectKind subjectKind,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResult.ok("Support activity", activityLog.list(
                new SupportActivityLog.ActivityQuery(agentUuid, action, subjectKind, subjectId, from, to, page, size))));
    }

    private static SupportAgent agent() {
        return SupportAgent.of(CurrentUser.get());
    }
}
