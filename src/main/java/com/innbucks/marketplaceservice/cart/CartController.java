package com.innbucks.marketplaceservice.cart;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.cart.dto.CartItemRequest;
import com.innbucks.marketplaceservice.cart.dto.CartQuantityRequest;
import com.innbucks.marketplaceservice.cart.dto.CartResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The buyer's cart — CUSTOMER-only, scoped to the caller by shape.
 *
 * <p>EVERY endpoint, mutations included, answers with the whole priced cart,
 * so the app never has to re-read after a change and can never show a
 * client-side sum that has drifted from what checkout will charge.
 */
@Tag(name = "Cart",
        description = "The signed-in buyer's cart. Stores quantities only — price, stock and "
                + "availability are resolved LIVE from the catalogue on every read, so the cart "
                + "never quotes a price the catalogue has moved past. The cart holds NO stock: "
                + "units are reserved once, at order creation. Every call returns the full priced "
                + "cart, including `checkoutReady` — gate the Checkout button on that.")
@RestController
@RequestMapping("/marketplace/cart")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CartController {

    private final CartService cartService;

    /** Newest line first: two of the Cotton Crew Tee in size M (an OPTION line -
     *  variantId, the option as it is now, and its unitPriceCents), then the
     *  speaker, a listing without options. Public so the public-test cart twin
     *  documents the very same body. */
    public static final String EXAMPLE_CART_200 = """
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
                      "categoryCode": "other",
                      "categoryName": "Other",
                      "condition": "NEW",
                      "city": "Harare",
                      "area": "Avondale",
                      "priceCents": 1999,
                      "currency": "USD",
                      "stockQty": 10,
                      "status": "ACTIVE",
                      "ratingAvg": null,
                      "reviewCount": 0,
                      "imageUrl": "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/image",
                      "imageUrls": [
                        "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/images/4d1c7e2a-9b3f-4a58-8e6d-0f2a1b3c4d5e"
                      ],
                      "seller": {
                        "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                        "displayName": "Sunrise Electronics",
                        "verified": true,
                        "since": "2026-04-01T09:15:00Z"
                      },
                      "hasVariants": true,
                      "options": [
                        { "name": "Size", "values": ["M", "L", "XL"] },
                        { "name": "Colour", "values": ["Black"] }
                      ],
                      "variants": [
                        { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", "values": ["M", "Black"],
                          "label": "M - Black", "priceCents": 1999, "stockQty": 4 },
                        { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", "values": ["L", "Black"],
                          "label": "L - Black", "priceCents": 1999, "stockQty": 0 },
                        { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574", "values": ["XL", "Black"],
                          "label": "XL - Black", "priceCents": 2299, "priceOverrideCents": 2299,
                          "stockQty": 6 }
                      ],
                      "maxPriceCents": 2299
                    },
                    "quantity": 2,
                    "lineTotalCents": 3998,
                    "addedAt": "2026-09-24T09:02:44Z",
                    "variantId": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352",
                    "variant": {
                      "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352",
                      "values": ["M", "Black"],
                      "label": "M - Black",
                      "priceCents": 1999,
                      "stockQty": 4
                    },
                    "unitPriceCents": 1999
                  },
                  {
                    "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "listing": {
                      "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "title": "Wireless Bluetooth Speaker",
                      "categoryCode": "tv-audio",
                      "categoryName": "TV & Audio",
                      "condition": "NEW",
                      "city": "Harare",
                      "area": "Avondale",
                      "priceCents": 2399,
                      "currency": "USD",
                      "stockQty": 150,
                      "status": "ACTIVE",
                      "ratingAvg": 5.0,
                      "reviewCount": 1,
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
                    "addedAt": "2026-09-14T11:02:44Z",
                    "unitPriceCents": 2399
                  }
                ],
                "lineCount": 2,
                "totalQuantity": 4,
                "subtotalCents": 8796,
                "currency": "USD",
                "checkoutReady": true
              }
            }""";

    private static final String EXAMPLE_CART_WITH_ISSUE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
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
                      "imageUrls": [],
                      "hasVariants": false,
                      "options": [],
                      "variants": [],
                      "maxPriceCents": 1550
                    },
                    "quantity": 5,
                    "lineTotalCents": 0,
                    "issue": {
                      "listingId": "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d",
                      "reason": "INSUFFICIENT_STOCK",
                      "message": "Only 3 left of Solar Lantern 20W",
                      "requestedQty": 5,
                      "availableQty": 3,
                      "unitPriceCents": 1550
                    },
                    "addedAt": "2026-09-14T10:40:02Z",
                    "unitPriceCents": 1550
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 5,
                "subtotalCents": 0,
                "currency": "USD",
                "checkoutReady": false
              }
            }""";

    /**
     * V19: two lines of the Cotton Crew Tee that cannot be bought as they
     * stand. Size L sold out - the line keeps its option and says so. And an
     * option the seller has since REMOVED from the listing (size S): the line
     * stays visible, {@code variant} is gone, but {@code variantId} is still
     * echoed so the app can remove the line with DELETE and that variantId.
     * Neither counts towards the subtotal.
     */
    private static final String EXAMPLE_CART_OPTION_ISSUES_200 = """
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
                      "maxPriceCents": 2299
                    },
                    "quantity": 1,
                    "lineTotalCents": 0,
                    "issue": {
                      "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                      "reason": "INSUFFICIENT_STOCK",
                      "message": "Cotton Crew Tee (L - Black) is sold out",
                      "requestedQty": 1,
                      "availableQty": 0,
                      "unitPriceCents": 1999,
                      "variantId": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463",
                      "variantLabel": "L - Black"
                    },
                    "addedAt": "2026-09-24T09:04:10Z",
                    "variantId": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463",
                    "variant": {
                      "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463",
                      "values": ["L", "Black"],
                      "label": "L - Black",
                      "priceCents": 1999,
                      "stockQty": 0
                    },
                    "unitPriceCents": 1999
                  },
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
                      "maxPriceCents": 2299
                    },
                    "quantity": 1,
                    "lineTotalCents": 0,
                    "issue": {
                      "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                      "reason": "VARIANT_UNAVAILABLE",
                      "message": "The option you chose for Cotton Crew Tee is no longer available - choose another",
                      "requestedQty": 1,
                      "unitPriceCents": 1999,
                      "variantId": "9f1d6b42-3a8e-4c17-b5d9-0e2f7c4a8b61"
                    },
                    "addedAt": "2026-09-24T09:01:15Z",
                    "variantId": "9f1d6b42-3a8e-4c17-b5d9-0e2f7c4a8b61",
                    "unitPriceCents": 1999
                  }
                ],
                "lineCount": 2,
                "totalQuantity": 2,
                "subtotalCents": 0,
                "currency": "USD",
                "checkoutReady": false
              }
            }""";

    public static final String EXAMPLE_VARIANT_REQUIRED_400 = """
            {
              "code": "variant_required",
              "message": "Choose an option before adding this item to your cart"
            }""";

    public static final String EXAMPLE_VARIANT_404 = """
            {
              "code": "variant_not_found",
              "message": "Variant not found"
            }""";

    private static final String EXAMPLE_BAD_VARIANT_ID_400 = """
            {
              "code": "invalid_parameter",
              "message": "'variantId' has a value we cannot read"
            }""";

    private static final String EXAMPLE_LISTING_404 = """
            {
              "code": "listing_not_found",
              "message": "Listing not found"
            }""";

    private static final String EXAMPLE_CART_FULL_409 = """
            {
              "code": "cart_full",
              "message": "Your cart holds the maximum of 20 different items. Remove one, or check out what you have."
            }""";

    private static final String EXAMPLE_QUANTITY_400 = """
            {
              "code": "invalid_quantity",
              "message": "You can buy up to 25 of one item per order"
            }""";

    private static final String EXAMPLE_FORBIDDEN_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role"
            }""";

    @GetMapping
    @Operation(summary = "Read my cart",
            description = "Prices every line against the live catalogue. A line whose listing went "
                    + "out of stock or off sale is STILL RETURNED, carrying an `issue` and "
                    + "contributing 0 to the subtotal — dropping it silently is how a shopper "
                    + "arrives at checkout with a total they do not recognise.\n\n"
                    + "**Options (V19).** A line for one option of a listing (a size, a colour) "
                    + "carries `variantId`, the option as it is now in `variant`, and "
                    + "`unitPriceCents` - the OPTION's price; `listing.priceCents` is only the "
                    + "listing's cheapest (\"from\") price, so price the line from "
                    + "`unitPriceCents`. Two sizes of one listing are two lines. An option that "
                    + "sold out carries `INSUFFICIENT_STOCK` naming it; one the seller removed "
                    + "carries `VARIANT_UNAVAILABLE`, loses `variant` but keeps `variantId`, so "
                    + "the app can remove it with `DELETE /items/{listingId}?variantId=`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's cart, priced live",
                    content = @Content(examples = {
                            @ExampleObject(name = "Ready to check out", value = EXAMPLE_CART_200),
                            @ExampleObject(name = "A line that needs fixing first",
                                    value = EXAMPLE_CART_WITH_ISSUE_200),
                            @ExampleObject(name = "An option sold out, another removed",
                                    value = EXAMPLE_CART_OPTION_ISSUES_200)})),
            @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_FORBIDDEN_403)))
    })
    public ResponseEntity<ApiResult<CartResponse>> getCart() {
        return ResponseEntity.ok(ApiResult.ok(cartService.getCart(CurrentUser.get())));
    }

    @PostMapping("/items")
    @Operation(summary = "Add to cart",
            description = "ADDS to whatever is already in the cart for this listing, capped at the "
                    + "per-item order limit rather than refused — a shopper tapping + past the cap "
                    + "wants the cap, not an error. The listing must exist but need NOT be in "
                    + "stock or on sale: the cart carries the issue and checkout is where it is "
                    + "finally refused. Returns the whole priced cart.\n\n"
                    + "**Options (V19).** On a listing with `hasVariants: true`, send the chosen "
                    + "`variants[].id` as `variantId` - without one the add is refused 400 "
                    + "`variant_required` (the same miss is a 422 at order creation; branch on the "
                    + "code, not the status). A `variantId` that is not one of THIS listing's "
                    + "options, or any `variantId` on a listing without options, is 404 "
                    + "`variant_not_found`. Each option is its own line: adding size M then size L "
                    + "makes two lines, and each counts towards the cart's line limit.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "One option of a listing", value = """
                                    {
                                      "listingId": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                                      "variantId": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352",
                                      "quantity": 2
                                    }"""),
                            @ExampleObject(name = "A listing without options", value = """
                                    {
                                      "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                                      "quantity": 2
                                    }""")})))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Added; the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_200))),
            @ApiResponse(responseCode = "400", description = "The listing sells options and none "
                    + "was chosen",
                    content = @Content(examples = @ExampleObject(name = "No option chosen",
                            value = EXAMPLE_VARIANT_REQUIRED_400))),
            @ApiResponse(responseCode = "404", description = "No such listing, or no such option "
                    + "of it",
                    content = @Content(examples = {
                            @ExampleObject(name = "No such listing", value = EXAMPLE_LISTING_404),
                            @ExampleObject(name = "Not one of this listing's options",
                                    value = EXAMPLE_VARIANT_404)})),
            @ApiResponse(responseCode = "409", description = "The cart already holds the maximum "
                    + "number of lines an order may carry (two options of one listing are two)",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_FULL_409)))
    })
    public ResponseEntity<ApiResult<CartResponse>> add(@Valid @RequestBody CartItemRequest request) {
        return ResponseEntity.ok(ApiResult.ok(cartService.add(
                CurrentUser.get(), request.listingId(), request.variantId(),
                request.quantityOrOne())));
    }

    @PutMapping("/items/{listingId}")
    @Operation(summary = "Set a cart line to an exact quantity",
            description = "The stepper control's write — idempotent, so a retry can never buy "
                    + "twice. Creates the line if it is not there yet. Quantity 0 is REFUSED (use "
                    + "DELETE) so that a zero can never be a silently-swallowed delete.\n\n"
                    + "An option line is addressed EXACTLY: `?variantId=` names the option, and "
                    + "no `variantId` means the line without one - so on a listing with options "
                    + "it is required (400 `variant_required`), and it must be one of that "
                    + "listing's options (404 `variant_not_found`).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Set; the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_200))),
            @ApiResponse(responseCode = "400", description = "Above the per-item order limit, no "
                    + "option named on a listing that sells options, or a variantId that is not "
                    + "a UUID",
                    content = @Content(examples = {
                            @ExampleObject(name = "Above the limit", value = EXAMPLE_QUANTITY_400),
                            @ExampleObject(name = "No option chosen",
                                    value = EXAMPLE_VARIANT_REQUIRED_400),
                            @ExampleObject(name = "Malformed variantId",
                                    value = EXAMPLE_BAD_VARIANT_ID_400)})),
            @ApiResponse(responseCode = "404", description = "No such listing, or no such option "
                    + "of it",
                    content = @Content(examples = {
                            @ExampleObject(name = "No such listing", value = EXAMPLE_LISTING_404),
                            @ExampleObject(name = "Not one of this listing's options",
                                    value = EXAMPLE_VARIANT_404)})),
            @ApiResponse(responseCode = "409", description = "The cart is full",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_FULL_409)))
    })
    public ResponseEntity<ApiResult<CartResponse>> setQuantity(
            @PathVariable UUID listingId,
            @Parameter(description = "The option this line is for (V19) - required on a listing "
                    + "with `hasVariants: true`, omitted otherwise",
                    example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352")
            @RequestParam(value = "variantId", required = false) UUID variantId,
            @Valid @RequestBody CartQuantityRequest request) {
        return ResponseEntity.ok(ApiResult.ok(cartService.setQuantity(
                CurrentUser.get(), listingId, variantId, request.quantity())));
    }

    @DeleteMapping("/items/{listingId}")
    @Operation(summary = "Remove a line from my cart",
            description = "Idempotent — removing a line that is not there is a 200 no-op, so the "
                    + "remove button can retry blindly. With `?variantId=` it removes exactly that "
                    + "option's line (even one the seller has since removed from the listing); "
                    + "without it, every line of the listing - which is what \"remove this "
                    + "item\" meant before options existed. Nothing else is validated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Removed (or was already absent); the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_200))),
            @ApiResponse(responseCode = "400", description = "A variantId that is not a UUID",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_BAD_VARIANT_ID_400)))
    })
    public ResponseEntity<ApiResult<CartResponse>> remove(
            @PathVariable UUID listingId,
            @Parameter(description = "The option line to remove (V19). Omit to remove EVERY line "
                    + "of this listing - the plain one and every option - which is what \"remove "
                    + "this item\" meant before options existed",
                    example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352")
            @RequestParam(value = "variantId", required = false) UUID variantId) {
        return ResponseEntity.ok(ApiResult.ok(
                cartService.remove(CurrentUser.get(), listingId, variantId)));
    }

    @DeleteMapping
    @Operation(summary = "Empty my cart",
            description = "Idempotent — clearing an empty cart is a 200 no-op.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The now-empty cart",
            content = @Content(examples = @ExampleObject(value = """
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "items": [],
                        "lineCount": 0,
                        "totalQuantity": 0,
                        "subtotalCents": 0,
                        "currency": "USD",
                        "checkoutReady": false
                      }
                    }"""))))
    public ResponseEntity<ApiResult<CartResponse>> clear() {
        return ResponseEntity.ok(ApiResult.ok(cartService.clear(CurrentUser.get())));
    }
}
