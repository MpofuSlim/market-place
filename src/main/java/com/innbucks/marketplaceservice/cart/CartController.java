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

    private static final String EXAMPLE_CART_200 = """
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
                        "verified": true
                      }
                    },
                    "quantity": 2,
                    "lineTotalCents": 4798,
                    "addedAt": "2026-09-14T11:02:44Z"
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 2,
                "subtotalCents": 4798,
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
                      "imageUrls": []
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
                    "addedAt": "2026-09-14T10:40:02Z"
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 5,
                "subtotalCents": 0,
                "currency": "USD",
                "checkoutReady": false
              }
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
                    + "arrives at checkout with a total they do not recognise.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's cart, priced live",
                    content = @Content(examples = {
                            @ExampleObject(name = "Ready to check out", value = EXAMPLE_CART_200),
                            @ExampleObject(name = "A line that needs fixing first",
                                    value = EXAMPLE_CART_WITH_ISSUE_200)})),
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
                    + "finally refused. Returns the whole priced cart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Added; the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_200))),
            @ApiResponse(responseCode = "404", description = "No such listing",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_LISTING_404))),
            @ApiResponse(responseCode = "409", description = "The cart already holds the maximum "
                    + "number of distinct listings an order may carry",
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
                    + "DELETE) so that a zero can never be a silently-swallowed delete.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Set; the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_200))),
            @ApiResponse(responseCode = "400", description = "Above the per-item order limit",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_QUANTITY_400))),
            @ApiResponse(responseCode = "404", description = "No such listing",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_LISTING_404))),
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
                    + "remove button can retry blindly.")
    @ApiResponses(@ApiResponse(responseCode = "200",
            description = "Removed (or was already absent); the whole cart comes back",
            content = @Content(examples = @ExampleObject(value = EXAMPLE_CART_200))))
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
