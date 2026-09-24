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
                        "verified": true,
                        "since": "2026-04-01T09:15:00Z"
                      },
                      "hasVariants": false,
                      "options": [],
                      "variants": [],
                      "maxPriceCents": 2399
                    },
                    "quantity": 2,
                    "lineTotalCents": 4798,
                    "unitPriceCents": 2399
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 2,
                "subtotalCents": 4798,
                "deliveryFeeCents": 300,
                "deliveryFees": [
                  { "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54", "feeCents": 300 }
                ],
                "totalCents": 5098,
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
                    "lineTotalCents": 4798,
                    "unitPriceCents": 2399
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

    /**
     * V19: Buy Now with a size chosen - one Cotton Crew Tee in XL (an option
     * with its own, dearer price) and two Solar Lanterns, delivered. The
     * {@code variant} block and {@code unitPriceCents} are what the line costs;
     * the listing's {@code priceCents} is only its cheapest option. One seller,
     * so ONE fee: the dearer of the two lines' fees to Harare. The order
     * examples place exactly this basket (MKT-4F2A9C1B77D0).
     */
    private static final String EXAMPLE_QUOTE_WITH_OPTION_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                    "listing": {
                      "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "title": "Cotton Crew Tee",
                      "priceCents": 1999,
                      "currency": "USD",
                      "stockQty": 22,
                      "status": "ACTIVE",
                      "imageUrl": "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/image",
                      "hasVariants": true,
                      "maxPriceCents": 2299
                    },
                    "quantity": 1,
                    "lineTotalCents": 2299,
                    "variantId": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574",
                    "variant": {
                      "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574",
                      "values": ["XL", "Black"],
                      "label": "XL - Black",
                      "priceCents": 2299,
                      "priceOverrideCents": 2299,
                      "stockQty": 6
                    },
                    "unitPriceCents": 2299
                  },
                  {
                    "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                    "listing": {
                      "id": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "title": "Solar Lantern 20W",
                      "priceCents": 1550,
                      "currency": "USD",
                      "stockQty": 3,
                      "status": "ACTIVE",
                      "hasVariants": false,
                      "maxPriceCents": 1550
                    },
                    "quantity": 2,
                    "lineTotalCents": 3100,
                    "unitPriceCents": 1550
                  }
                ],
                "lineCount": 2,
                "totalQuantity": 3,
                "subtotalCents": 5399,
                "deliveryFeeCents": 300,
                "deliveryFees": [
                  { "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54", "feeCents": 300 }
                ],
                "totalCents": 5699,
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
                  }
                ]
              }
            }""";

    /**
     * V19: the Cotton Crew Tee sold in sizes, sent without a {@code variantId}.
     * The line keeps its listing (so the app can show the picker) and prices at
     * the listing's from-price, but contributes nothing and is listed in
     * {@code rejections} as {@code VARIANT_REQUIRED}. No sellable line, so no
     * seller owes a delivery fee.
     */
    private static final String EXAMPLE_QUOTE_VARIANT_REQUIRED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                    "listing": {
                      "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "title": "Cotton Crew Tee",
                      "priceCents": 1999,
                      "currency": "USD",
                      "stockQty": 10,
                      "status": "ACTIVE",
                      "hasVariants": true,
                      "options": [
                        { "name": "Size", "values": ["M", "L", "XL"] },
                        { "name": "Colour", "values": ["Black"] }
                      ],
                      "maxPriceCents": 2299
                    },
                    "quantity": 1,
                    "lineTotalCents": 0,
                    "issue": {
                      "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                      "reason": "VARIANT_REQUIRED",
                      "message": "Choose a Size and Colour for Cotton Crew Tee",
                      "requestedQty": 1,
                      "unitPriceCents": 1999
                    },
                    "unitPriceCents": 1999
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 1,
                "subtotalCents": 0,
                "deliveryFeeCents": 0,
                "deliveryFees": [],
                "totalCents": 0,
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
                "rejections": [
                  {
                    "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                    "reason": "VARIANT_REQUIRED",
                    "message": "Choose a Size and Colour for Cotton Crew Tee",
                    "requestedQty": 1,
                    "unitPriceCents": 1999
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
                    + "**Options (V19).** A line for a listing sold in options (`hasVariants: "
                    + "true` - sizes, colours) names the chosen one as `variantId`; two sizes of "
                    + "one listing are two lines. Send each option once: the order refuses the "
                    + "same option twice (400 `duplicate_listing`). Such a line prices at the "
                    + "OPTION's price - read "
                    + "`unitPriceCents` on the line, never the listing's `priceCents`, which is "
                    + "only its cheapest option. A line that names no option comes back in "
                    + "`rejections` as `VARIANT_REQUIRED`, and one whose option is gone (or is "
                    + "not that listing's) as `VARIANT_UNAVAILABLE`; both keep the listing so the "
                    + "app can show the picker.\n\n"
                    + "The totals are what the order will produce for the same body, as long as "
                    + "nothing sells out in between. They are not a promise the order honours — "
                    + "the order recomputes from the listings itself, because a quote a client "
                    + "could replay is a price a client could choose.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Check out the cart", value = """
                                    {
                                      "fromCart": true,
                                      "deliveryMethod": "DELIVERY",
                                      "deliveryAddressId": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45"
                                    }"""),
                            @ExampleObject(name = "Buy Now, a size chosen", value = """
                                    {
                                      "items": [
                                        { "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                                          "quantity": 1,
                                          "variantId": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574" },
                                        { "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                                          "quantity": 2 }
                                      ],
                                      "deliveryMethod": "DELIVERY",
                                      "deliveryAddressId": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45"
                                    }""")})))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The priced checkout preview",
                    content = @Content(examples = {
                            @ExampleObject(name = "Ready to order", value = EXAMPLE_QUOTE_200),
                            @ExampleObject(name = "Ready to order, a size chosen",
                                    value = EXAMPLE_QUOTE_WITH_OPTION_200),
                            @ExampleObject(name = "Something needs fixing first",
                                    value = EXAMPLE_QUOTE_NOT_READY_200),
                            @ExampleObject(name = "No size chosen",
                                    value = EXAMPLE_QUOTE_VARIANT_REQUIRED_200),
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
