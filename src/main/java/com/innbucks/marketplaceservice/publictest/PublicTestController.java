package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.cart.dto.CartItemRequest;
import com.innbucks.marketplaceservice.cart.dto.CartQuantityRequest;
import com.innbucks.marketplaceservice.cart.dto.CartResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutOptionsResponse;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteRequest;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteResponse;
import com.innbucks.marketplaceservice.delivery.DeliveryAddressService;
import com.innbucks.marketplaceservice.delivery.dto.AddressRequest;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.favorite.FavoriteService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Unauthenticated PRE-CHECKOUT surface, for building the app before
 * {@code POST /auth/exchange} is switched on.
 *
 * <p>The super app's customers authenticate at the InnBucks middleware, which
 * does not yet sign the assertion user-service trades for a fleet CUSTOMER
 * token — so every {@code hasRole('CUSTOMER')} endpoint here is unreachable
 * from a real session. This surface exists so the basket, address book,
 * wishlist and checkout-quote screens can be built against real data in the
 * meantime. It is the marketplace sibling of loyalty's
 * {@code /loyalty/public/**}, and follows its rules deliberately.
 *
 * <h2>What is NOT here, and will not be</h2>
 * <b>Order creation, payment, fulfilment, disputes and collect codes are
 * absent by design.</b> The line is drawn at anything with an effect that
 * leaves this service:
 * <ul>
 *   <li>An order carries {@code buyerMsisdn}, which payment-service treats as
 *       the payer, and on the EcoCash rail that is the handset an unsolicited
 *       PIN prompt is delivered to. An unauthenticated caller who could name
 *       that number would have a phishing tool that works on live phones
 *       whatever cell it was fired from.</li>
 *   <li>An order also reserves real merchant stock, and escrow, settlement and
 *       dispute all key on a buyer identity that must be real to mean anything.</li>
 * </ul>
 * Everything on this rail writes only rows this service owns, sends nothing,
 * reserves nothing, and moves no money. Keep it that way: if a new endpoint
 * here would cause an SMS, a payment or a stock hold, it does not belong on
 * this surface.
 *
 * <h2>Rules for anything added under this prefix</h2>
 * <ol>
 *   <li><b>Off unless explicitly switched on.</b> Gated by
 *       {@code marketplace.public-test.enabled}, default {@code false}. A cell
 *       that forgets it serves 404s, which is the safe direction.</li>
 *   <li><b>Nothing here re-checks the api-key.</b>
 *       {@code PublicTestApiKeyFilter} covers the prefix by shape, so a mapping
 *       added to this class is gated the moment it exists. Do not move that
 *       check into the methods, where the one that forgets it is a live
 *       un-gated endpoint.</li>
 *   <li><b>Never re-implement a service method.</b> Each endpoint calls exactly
 *       what its authenticated twin calls, so the real rules — stock, pricing,
 *       caps, the one-default-address invariant — still apply and this surface
 *       cannot drift into lying about production.</li>
 *   <li><b>Every call is logged</b> at WARN with the handle, so there is a
 *       trail of what was done while the switch was on.</li>
 * </ol>
 *
 * <h2>The identity is derived, not supplied</h2>
 * The {@code handle} in the path is free-form and is hashed into a
 * <b>version-5</b> UUID by {@link PublicTestIdentity}. Real customers carry
 * version-4 uuids, so a caller here can never address a real customer's basket,
 * address book or wishlist — see that class for why this is a structural
 * guarantee rather than an improbability.
 *
 * <p><b>Do not enable this on a production cell.</b> It is a build aid with a
 * deliberately narrow blast radius, not an authentication scheme.
 */
@RestController
@RequestMapping("/marketplace/public")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Public (TEST ONLY — no auth)",
     description = """
             **Pre-checkout endpoints for frontend testing. No bearer token, no role, no tenant header.**

             These exist so the basket, address book, wishlist and checkout-quote screens can be built \
             against real data before the super app's login is wired to the fleet. They are disabled \
             (404) unless the cell sets `MARKETPLACE_PUBLIC_TEST_ENABLED=true`.

             **The `handle` in the path is the identity** — any string you choose (`alice`, a device \
             id). Keep using the same one and you get the same basket back. It is hashed into an \
             internal buyer id and never stored as you typed it.

             **Optional `x-api-key`.** A cell MAY put a shared key in front of this whole prefix \
             (`MARKETPLACE_PUBLIC_TEST_API_KEY`). Where one is set, every call needs the header and a \
             missing or wrong value is a `401`; where it is blank — the default — no header is needed. \
             Ask which applies to the cell you are pointed at. The key identifies the APP, not the \
             customer.

             **There is no order, payment or fulfilment endpoint here, and there will not be.** Placing \
             an order names the phone that receives a payment PIN prompt and reserves a merchant's \
             stock; neither belongs behind an unauthenticated path. Those ship against \
             `POST /auth/exchange` — see `Auth-Exchange-Frontend-Integration.md`.

             Every other tag in this document requires a bearer token. If you are looking for the \
             endpoint you will SHIP against, it is there, not here.""")
@SecurityRequirements   // documents "no auth" — overrides the global bearerAuth requirement
public class PublicTestController {

    private static final String EXAMPLE_CART = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "title": "Hand-woven sisal basket",
                    "quantity": 2,
                    "unitPriceCents": 2499,
                    "lineTotalCents": 4998,
                    "issue": null
                  }
                ],
                "lineCount": 1,
                "totalQuantity": 2,
                "subtotalCents": 4998,
                "currency": "USD",
                "checkoutReady": true
              }
            }""";

    private static final String EXAMPLE_DISABLED_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "Not found",
              "data": null
            }""";

    private static final String EXAMPLE_BAD_KEY_401 = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing API key",
              "data": null
            }""";

    private static final String EXAMPLE_BAD_HANDLE_400 = """
            {
              "code": "invalid_handle",
              "message": "The buyer handle must be at most 64 characters.",
              "data": null
            }""";

    private final CartService cartService;
    private final DeliveryAddressService addressService;
    private final FavoriteService favoriteService;
    private final CheckoutService checkoutService;
    private final MarketplaceMetrics metrics;

    /**
     * Master switch. Default {@code false} so the endpoints are absent unless a
     * cell deliberately turns them on — the same fail-closed posture
     * {@code ProductionSecretsGuard} takes for secrets.
     */
    @Value("${marketplace.public-test.enabled:false}")
    private boolean enabled;

    // ---------------------------------------------------------------- cart

    @GetMapping("/buyers/{handle}/cart")
    @Operation(summary = "[TEST] Read this handle's cart",
            description = "Prices the basket live against the catalogue, exactly as the "
                    + "authenticated `GET /marketplace/cart` does. A line that has gone out of "
                    + "stock stays visible carrying its `issue` and contributing 0.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The priced cart",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART))),
            @ApiResponse(responseCode = "400", description = "Blank or over-long handle",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_BAD_HANDLE_400))),
            @ApiResponse(responseCode = "401", description = "The cell gates this prefix and the key was missing or wrong",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_BAD_KEY_401))),
            @ApiResponse(responseCode = "404", description = "The test surface is not enabled on this cell",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<CartResponse>> cart(@PathVariable String handle) {
        AuthenticatedUser buyer = actAs(handle, "cart_read");
        return ResponseEntity.ok(ApiResult.ok(cartService.getCart(buyer)));
    }

    @PostMapping("/buyers/{handle}/cart/items")
    @Operation(summary = "[TEST] Add to this handle's cart",
            description = "ADDS to whatever is already there for that listing, capped at the "
                    + "per-item order limit rather than refused. Holds no stock.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Added; the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART))),
            @ApiResponse(responseCode = "404", description = "No such listing, or the surface is off",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "The cart already holds the maximum number of distinct listings")
    })
    public ResponseEntity<ApiResult<CartResponse>> addToCart(
            @PathVariable String handle, @Valid @RequestBody CartItemRequest request) {
        AuthenticatedUser buyer = actAs(handle, "cart_add");
        return ResponseEntity.ok(ApiResult.ok(
                cartService.add(buyer, request.listingId(), request.quantityOrOne())));
    }

    @PutMapping("/buyers/{handle}/cart/items/{listingId}")
    @Operation(summary = "[TEST] Set a cart line to an exact quantity",
            description = "The stepper control's write — idempotent. Quantity 0 is REFUSED "
                    + "(use DELETE) so a zero can never be a silently-swallowed delete.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Set; the whole cart comes back",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CART))),
            @ApiResponse(responseCode = "400", description = "Above the per-item order limit"),
            @ApiResponse(responseCode = "404", description = "No such listing, or the surface is off",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<CartResponse>> setCartQuantity(
            @PathVariable String handle,
            @PathVariable UUID listingId,
            @Valid @RequestBody CartQuantityRequest request) {
        AuthenticatedUser buyer = actAs(handle, "cart_set_quantity");
        return ResponseEntity.ok(ApiResult.ok(
                cartService.setQuantity(buyer, listingId, request.quantity())));
    }

    @DeleteMapping("/buyers/{handle}/cart/items/{listingId}")
    @Operation(summary = "[TEST] Remove a line from this handle's cart",
            description = "Idempotent — removing a line that is not there is a 200 no-op.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Removed; the whole cart comes back",
            content = @Content(examples = @ExampleObject(value = EXAMPLE_CART))))
    public ResponseEntity<ApiResult<CartResponse>> removeFromCart(
            @PathVariable String handle, @PathVariable UUID listingId) {
        AuthenticatedUser buyer = actAs(handle, "cart_remove");
        return ResponseEntity.ok(ApiResult.ok(cartService.remove(buyer, listingId)));
    }

    @DeleteMapping("/buyers/{handle}/cart")
    @Operation(summary = "[TEST] Empty this handle's cart",
            description = "Idempotent — clearing an empty cart is a 200 no-op.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The now-empty cart"))
    public ResponseEntity<ApiResult<CartResponse>> clearCart(@PathVariable String handle) {
        AuthenticatedUser buyer = actAs(handle, "cart_clear");
        return ResponseEntity.ok(ApiResult.ok(cartService.clear(buyer)));
    }

    // ----------------------------------------------------------- addresses

    @GetMapping("/buyers/{handle}/addresses")
    @Operation(summary = "[TEST] This handle's address book",
            description = "Newest first, the default entry flagged. Exactly one entry is the "
                    + "default whenever the book is non-empty.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The address book"))
    public ResponseEntity<ApiResult<List<AddressResponse>>> addresses(@PathVariable String handle) {
        AuthenticatedUser buyer = actAs(handle, "address_list");
        return ResponseEntity.ok(ApiResult.ok(addressService.listMine(buyer)));
    }

    @GetMapping("/buyers/{handle}/addresses/{id}")
    @Operation(summary = "[TEST] One saved address",
            description = "404 when the address belongs to a different handle — the same "
                    + "owner-masked 404 the authenticated surface gives.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The address"),
            @ApiResponse(responseCode = "404", description = "Not this handle's address, or the surface is off")
    })
    public ResponseEntity<ApiResult<AddressResponse>> address(
            @PathVariable String handle, @PathVariable UUID id) {
        AuthenticatedUser buyer = actAs(handle, "address_get");
        return ResponseEntity.ok(ApiResult.ok(addressService.getMine(buyer, id)));
    }

    @PostMapping("/buyers/{handle}/addresses")
    @Operation(summary = "[TEST] Save a delivery address",
            description = "The first address saved becomes the default. `recipientMsisdn` is "
                    + "normalised to E.164 and a malformed number is refused, exactly as on the "
                    + "authenticated surface — nothing is sent to it.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the msisdn is malformed")
    })
    public ResponseEntity<ApiResult<AddressResponse>> createAddress(
            @PathVariable String handle, @Valid @RequestBody AddressRequest request) {
        AuthenticatedUser buyer = actAs(handle, "address_create");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created(addressService.create(buyer, request)));
    }

    @PutMapping("/buyers/{handle}/addresses/{id}")
    @Operation(summary = "[TEST] Edit a saved address",
            description = "Replaces the whole entry. Editing an address never redirects an order "
                    + "already placed — orders snapshot their destination.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "404", description = "Not this handle's address, or the surface is off")
    })
    public ResponseEntity<ApiResult<AddressResponse>> updateAddress(
            @PathVariable String handle, @PathVariable UUID id,
            @Valid @RequestBody AddressRequest request) {
        AuthenticatedUser buyer = actAs(handle, "address_update");
        return ResponseEntity.ok(ApiResult.ok(addressService.update(buyer, id, request)));
    }

    @PutMapping("/buyers/{handle}/addresses/{id}/default")
    @Operation(summary = "[TEST] Make this the default address",
            description = "Demotes the incumbent first, so the one-default-per-buyer index is "
                    + "never momentarily violated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promoted"),
            @ApiResponse(responseCode = "404", description = "Not this handle's address, or the surface is off")
    })
    public ResponseEntity<ApiResult<AddressResponse>> makeDefaultAddress(
            @PathVariable String handle, @PathVariable UUID id) {
        AuthenticatedUser buyer = actAs(handle, "address_make_default");
        return ResponseEntity.ok(ApiResult.ok(addressService.makeDefault(buyer, id)));
    }

    @DeleteMapping("/buyers/{handle}/addresses/{id}")
    @Operation(summary = "[TEST] Delete a saved address",
            description = "Deleting the default promotes the most recent survivor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "Not this handle's address, or the surface is off")
    })
    public ResponseEntity<ApiResult<Void>> deleteAddress(
            @PathVariable String handle, @PathVariable UUID id) {
        AuthenticatedUser buyer = actAs(handle, "address_delete");
        addressService.delete(buyer, id);
        return ResponseEntity.ok(ApiResult.ok("Deleted", null));
    }

    // ----------------------------------------------------------- favorites

    @PutMapping("/buyers/{handle}/favorites/{listingId}")
    @Operation(summary = "[TEST] Add a listing to this handle's wishlist",
            description = "Idempotent — a repeat add is a 200 no-op and never bumps the "
                    + "favourited-at ordering. The listing must exist, in ANY status.\n\n"
                    + "A restock of a favourited listing notifies its favouriters through "
                    + "user-service. A handle here resolves to a buyer id that does not exist "
                    + "there, so that notification reaches nobody — which is why this endpoint "
                    + "is safe to expose and an order endpoint is not.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Favourited (or already was)"),
            @ApiResponse(responseCode = "404", description = "No such listing, or the surface is off")
    })
    public ResponseEntity<ApiResult<Void>> addFavorite(
            @PathVariable String handle, @PathVariable UUID listingId) {
        AuthenticatedUser buyer = actAs(handle, "favorite_add");
        favoriteService.add(buyer, listingId);
        return ResponseEntity.ok(ApiResult.ok("Added to favorites", null));
    }

    @DeleteMapping("/buyers/{handle}/favorites/{listingId}")
    @Operation(summary = "[TEST] Remove a listing from this handle's wishlist",
            description = "Idempotent — removing something that was never favourited is a 200 no-op.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Removed (or was already absent)"))
    public ResponseEntity<ApiResult<Void>> removeFavorite(
            @PathVariable String handle, @PathVariable UUID listingId) {
        AuthenticatedUser buyer = actAs(handle, "favorite_remove");
        favoriteService.remove(buyer, listingId);
        return ResponseEntity.ok(ApiResult.ok("Removed from favorites", null));
    }

    @GetMapping("/buyers/{handle}/favorites")
    @Operation(summary = "[TEST] This handle's wishlist",
            description = "Newest-favourited first, each entry carrying the listing's CURRENT "
                    + "status so the app can render \"no longer available\".")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The wishlist page"))
    public ResponseEntity<ApiResult<ListingPageResponse>> favorites(
            @PathVariable String handle,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuthenticatedUser buyer = actAs(handle, "favorite_list");
        return ResponseEntity.ok(ApiResult.ok(favoriteService.listMine(buyer, page, size)));
    }

    // ------------------------------------------------------------ checkout

    @PostMapping("/buyers/{handle}/checkout/quote")
    @Operation(summary = "[TEST] Price a basket without ordering",
            description = "Reserves NOTHING and creates no order. A basket with problems is a "
                    + "**200** with `checkoutReady: false` and every failing line in "
                    + "`rejections` — the shopper has to see the basket to fix it. Only a "
                    + "malformed request or a missing address is an error.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The quote, ready or not"),
            @ApiResponse(responseCode = "400", description = "Malformed request, or DELIVERY with no address"),
            @ApiResponse(responseCode = "404", description = "The surface is off, or the address is not this handle's",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<CheckoutQuoteResponse>> quote(
            @PathVariable String handle, @Valid @RequestBody CheckoutQuoteRequest request) {
        AuthenticatedUser buyer = actAs(handle, "checkout_quote");
        return ResponseEntity.ok(ApiResult.ok(checkoutService.quote(buyer, request)));
    }

    @GetMapping("/checkout/options")
    @Operation(summary = "[TEST] Delivery methods and payment rails this cell offers",
            description = "Buyer-independent, so it takes no handle. The payment rails listed "
                    + "are what this cell is configured to advertise; paying still happens at "
                    + "payment-service against a real order, which this surface cannot create.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The options"),
            @ApiResponse(responseCode = "404", description = "The test surface is not enabled on this cell",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<CheckoutOptionsResponse>> options() {
        requireEnabled();
        metrics.publicTestCall("checkout_options");
        log.warn("[public-test] checkout_options");
        return ResponseEntity.ok(ApiResult.ok(checkoutService.options()));
    }

    // ------------------------------------------------------------ internals

    /**
     * The one place the surface's switch, its audit line and its identity
     * derivation are applied — so an endpoint added later gets all three by
     * calling it, and an endpoint that forgets to call it has no buyer to act
     * as and cannot compile into something that works.
     */
    private AuthenticatedUser actAs(String handle, String operation) {
        requireEnabled();
        AuthenticatedUser buyer = PublicTestIdentity.buyerFor(handle);
        metrics.publicTestCall(operation);
        // WARN, not INFO: every line here is an unauthenticated write or read
        // that would normally have required a token, and the point is that it
        // stands out in a log nobody was expecting it in. The handle is the
        // caller's own label, never a real customer's identifier.
        log.warn("[public-test] {} handle={} buyer={}", operation, handle, buyer.uuid());
        return buyer;
    }

    /**
     * 404, never 403 — "this endpoint does not exist here" is the honest answer
     * on a cell that has not enabled the surface, and it tells a prober nothing
     * about what the build is capable of.
     */
    private void requireEnabled() {
        if (!enabled) {
            // ApiException, not ResponseStatusException: the latter falls
            // through GlobalExceptionHandler's catch-all and renders as a 500,
            // which would make a cell that simply has the feature off look
            // broken.
            throw ApiException.notFound("not_found", "Not found");
        }
    }
}
