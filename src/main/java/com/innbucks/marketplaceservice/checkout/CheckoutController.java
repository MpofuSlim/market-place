package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutOptionsResponse;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteRequest;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout — the step between the cart and the order.
 *
 * <p>CUSTOMER-only, because both endpoints answer questions about a specific
 * shopper's basket and saved addresses.
 */
@Tag(name = "Checkout",
        description = "The priced preview between the cart and the order. `POST /quote` totals a "
                + "basket with delivery and tells you whether an order would be accepted — "
                + "RESERVING NOTHING, so it is free to call as the shopper changes their mind. "
                + "`GET /options` is the static per-cell picture: how goods can be received and "
                + "which payment rails this cell can collect on.\n\n"
                + "**Paying is a different service.** Marketplace-service never collects money. "
                + "Once an order exists, settle it with `POST /payments` carrying "
                + "`{orderType: \"MARKETPLACE\", orderRef, paymentRail}` — the order's own "
                + "`payment` block repeats all of that, so the app does not have to hardcode it.")
@RestController
@RequestMapping("/marketplace/checkout")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CheckoutController {

    private final CheckoutService checkoutService;

    private static final String EXAMPLE_QUOTE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "listing": {
                      "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "title": "Wireless Bluetooth Speaker",
                      "priceCents": 2399,
                      "currency": "USD",
                      "stockQty": 150,
                      "status": "ACTIVE",
                      "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                      "imageUrls": [
                        "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"
                      ],
                      "seller": {
                        "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                        "displayName": "Sunrise Electronics",
                        "verified": true
                      }
                    },
                    "quantity": 2,
                    "lineTotalCents": 4798
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 2,
                "subtotalCents": 4798,
                "deliveryFeeCents": 200,
                "deliveryFees": [
                  { "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54", "feeCents": 200 }
                ],
                "totalCents": 4998,
                "currency": "USD",
                "deliveryMethod": "DELIVERY",
                "deliveryMethods": ["DELIVERY", "COLLECTION"],
                "deliveryAddress": {
                  "id": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45",
                  "label": "Home",
                  "recipientName": "Tariro Moyo",
                  "recipientMsisdn": "+263771234567",
                  "line1": "14 Samora Machel Ave",
                  "line2": "Flat 3B",
                  "city": "Harare",
                  "townCode": "harare",
                  "area": "Avondale",
                  "landmark": "Opposite the clinic, blue gate",
                  "defaultAddress": true,
                  "createdAt": "2026-08-01T08:10:22Z",
                  "updatedAt": "2026-08-01T08:10:22Z"
                },
                "checkoutReady": true,
                "paymentMethods": [
                  {
                    "rail": "INNBUCKS_CODE",
                    "label": "InnBucks app",
                    "description": "Approve the payment code in your InnBucks app.",
                    "completion": "APPROVE_IN_APP"
                  },
                  {
                    "rail": "ECOCASH",
                    "label": "EcoCash",
                    "description": "We send a PIN prompt to the phone on this order. Approve it to pay.",
                    "completion": "PHONE_PROMPT"
                  }
                ]
              }
            }""";

    /** A COLLECTION quote: each seller's collection point is resolved (the
     *  buyer's choice, else the seller's default). Same listing and seller as
     *  {@link #EXAMPLE_QUOTE_200}; the point matches the seller portal's
     *  collection-point examples. */
    private static final String EXAMPLE_QUOTE_COLLECTION_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "quantity": 2,
                    "lineTotalCents": 4798
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 2,
                "subtotalCents": 4798,
                "deliveryFeeCents": 0,
                "totalCents": 4798,
                "currency": "USD",
                "deliveryMethod": "COLLECTION",
                "deliveryMethods": ["DELIVERY", "COLLECTION"],
                "checkoutReady": true,
                "collectionPoints": [
                  {
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "collectionPoint": {
                      "id": "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e",
                      "name": "Avondale shop",
                      "townCode": "harare",
                      "townName": "Harare",
                      "line1": "14 Samora Machel Ave",
                      "line2": "Shop 3, Avondale Shopping Centre",
                      "area": "Avondale",
                      "landmark": "Next to the pharmacy",
                      "phone": "+263242123456",
                      "openingHoursSummary": "Mon-Fri 08:00-17:00, Sat 08:00-13:00",
                      "openNow": true
                    }
                  }
                ]
              }
            }""";

    public static final String EXAMPLE_UNKNOWN_COLLECTION_POINT_400 = """
            {
              "code": "unknown_collection_point",
              "message": "That collection point is not one of this seller's - choose again from their collection points",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "collectionPointId": "9b3e41d7-6c2a-4f18-8d5e-0a7c2b9f1e36"
              }
            }""";

    public static final String EXAMPLE_DUPLICATE_COLLECTION_POINT_400 = """
            {
              "code": "duplicate_collection_point_choice",
              "message": "collectionPoints names the same seller more than once"
            }""";

    private static final String EXAMPLE_QUOTE_NOT_READY_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                    "quantity": 5,
                    "lineTotalCents": 0,
                    "issue": {
                      "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                      "reason": "LISTING_UNAVAILABLE",
                      "message": "Listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d is not available",
                      "requestedQty": 5
                    }
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 5,
                "subtotalCents": 0,
                "deliveryFeeCents": 0,
                "totalCents": 0,
                "currency": "USD",
                "deliveryMethod": "COLLECTION",
                "deliveryMethods": ["DELIVERY", "COLLECTION"],
                "rejections": [
                  {
                    "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                    "reason": "LISTING_UNAVAILABLE",
                    "message": "Listing 9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d is not available",
                    "requestedQty": 5
                  }
                ],
                "checkoutReady": false,
                "paymentMethods": [
                  {
                    "rail": "INNBUCKS_CODE",
                    "label": "InnBucks app",
                    "description": "Approve the payment code in your InnBucks app.",
                    "completion": "APPROVE_IN_APP"
                  }
                ]
              }
            }""";

    private static final String EXAMPLE_OPTIONS_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "deliveryMethods": ["DELIVERY", "COLLECTION"],
                "deliveryFeeCents": 0,
                "currency": "USD",
                "paymentMethods": [
                  {
                    "rail": "INNBUCKS_CODE",
                    "label": "InnBucks app",
                    "description": "Approve the payment code in your InnBucks app.",
                    "completion": "APPROVE_IN_APP"
                  },
                  {
                    "rail": "ZIMSWITCH_CARD",
                    "label": "Debit or credit card",
                    "description": "Pay with your card on a secure checkout page.",
                    "completion": "CARD_WIDGET"
                  }
                ],
                "paymentEndpoint": "POST /payments",
                "paymentOrderType": "MARKETPLACE"
              }
            }""";

    private static final String EXAMPLE_ADDRESS_REQUIRED_400 = """
            {
              "code": "delivery_address_required",
              "message": "Choose a delivery address, or add one first"
            }""";

    private static final String EXAMPLE_AMBIGUOUS_400 = """
            {
              "code": "ambiguous_basket",
              "message": "Send either fromCart or items, not both"
            }""";

    private static final String EXAMPLE_METHOD_422 = """
            {
              "code": "delivery_method_unavailable",
              "message": "DELIVERY is not available in this market"
            }""";

    private static final String EXAMPLE_FORBIDDEN_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role"
            }""";

    @PostMapping("/quote")
    @Operation(summary = "Price a basket for checkout",
            description = "Totals a basket (the cart, or explicit items for Buy Now) with the "
                    + "delivery fee and the chosen destination, and says whether "
                    + "`POST /marketplace/orders` with the same body would be accepted.\n\n"
                    + "**Reserves no stock and changes nothing** — call it as often as the shopper "
                    + "changes method or address. A basket with problems still returns 200 with "
                    + "`checkoutReady: false` and every failing line in `rejections`, because the "
                    + "shopper needs to SEE the basket in order to fix it; only a malformed "
                    + "request, a missing address or a stale collection-point choice is an "
                    + "error.\n\n"
                    + "**Delivery is per seller and per town.** Each listing names the towns its "
                    + "seller delivers to and the fee for each. A DELIVERY quote needs every line "
                    + "to deliver to the address's town — a line that does not comes back in "
                    + "`rejections` as `NOT_DELIVERED_TO_TOWN`. Each seller ships one parcel, so "
                    + "each seller charges ONE fee (their dearest line to that town), listed in "
                    + "`deliveryFees`; `deliveryFeeCents` is their sum.\n\n"
                    + "**Collection is per seller too.** A COLLECTION quote lists, in "
                    + "`collectionPoints`, where each seller's goods would be collected: the point "
                    + "named in the request's optional `collectionPoints` "
                    + "(`[{merchantId, collectionPointId}]`), else the seller's default. A seller "
                    + "with no point is listed without one - collection is arranged with them. A "
                    + "choice that is not that seller's point (removed since the screen loaded) is "
                    + "a 400 `unknown_collection_point` whose `data` names the seller; send the "
                    + "same `collectionPoints` on the order.\n\n"
                    + "The totals are what the order will produce for the same body, as long as "
                    + "nothing sells out in between. They are not a promise the order honours — "
                    + "the order recomputes from the listings itself, because a quote a client "
                    + "could replay is a price a client could choose.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The priced checkout preview",
                    content = @Content(examples = {
                            @ExampleObject(name = "Ready to order", value = EXAMPLE_QUOTE_200),
                            @ExampleObject(name = "Something needs fixing first",
                                    value = EXAMPLE_QUOTE_NOT_READY_200),
                            @ExampleObject(name = "Collection, point resolved per seller",
                                    value = EXAMPLE_QUOTE_COLLECTION_200)})),
            @ApiResponse(responseCode = "400",
                    description = "Both/neither basket source, an empty cart, a DELIVERY quote "
                            + "with no address to send to, a collection-point choice that is not "
                            + "that seller's, or one seller named twice in collectionPoints",
                    content = @Content(examples = {
                            @ExampleObject(name = "No address", value = EXAMPLE_ADDRESS_REQUIRED_400),
                            @ExampleObject(name = "Two basket sources", value = EXAMPLE_AMBIGUOUS_400),
                            @ExampleObject(name = "Stale collection point",
                                    value = EXAMPLE_UNKNOWN_COLLECTION_POINT_400),
                            @ExampleObject(name = "Seller named twice",
                                    value = EXAMPLE_DUPLICATE_COLLECTION_POINT_400)})),
            @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_FORBIDDEN_403))),
            @ApiResponse(responseCode = "422", description = "The cell does not offer that delivery "
                    + "method, or the chosen address has no town (saved before towns existed, "
                    + "with a city that matched none) — edit the address and pick its town",
                    content = @Content(examples = {
                            @ExampleObject(name = "Method not offered", value = EXAMPLE_METHOD_422),
                            @ExampleObject(name = "Address has no town", value = """
                                    {
                                      "code": "address_town_required",
                                      "message": "Choose the town for this address before using it for delivery"
                                    }""")}))
    })
    public ResponseEntity<ApiResult<CheckoutQuoteResponse>> quote(
            @Valid @RequestBody CheckoutQuoteRequest request) {
        return ResponseEntity.ok(ApiResult.ok(checkoutService.quote(CurrentUser.get(), request)));
    }

    @GetMapping("/options")
    @Operation(summary = "What this cell offers at checkout",
            description = "The per-cell picture the app can cache: delivery methods, the flat "
                    + "delivery fee, and the payment rails this cell is actually provisioned for.\n\n"
                    + "**Read the payment rails from here rather than hardcoding them.** A cell "
                    + "advertises only the rails it can collect on — an app that assumed all three "
                    + "would offer a buyer a method that dead-ends in a 503 from the payments "
                    + "service.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "This cell's checkout options",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_OPTIONS_200))),
            @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_FORBIDDEN_403)))
    })
    public ResponseEntity<ApiResult<CheckoutOptionsResponse>> options() {
        return ResponseEntity.ok(ApiResult.ok(checkoutService.options()));
    }
}
