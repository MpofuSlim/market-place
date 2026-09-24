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
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.fulfilment.dto.CollectCodeResponse;
import com.innbucks.marketplaceservice.fulfilment.tracking.ParcelTrackingResponse;
import com.innbucks.marketplaceservice.fulfilment.tracking.ParcelTrackingService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.OrderService;
import com.innbucks.marketplaceservice.order.dto.CreateOrderRequest;
import com.innbucks.marketplaceservice.order.dto.OrderPageResponse;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.review.ReviewService;
import com.innbucks.marketplaceservice.review.dto.ReviewRequest;
import com.innbucks.marketplaceservice.review.dto.ReviewResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.DisputeService;
import com.innbucks.marketplaceservice.settlement.dto.DisputeRequest;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <h2>The order rail, and the price of it</h2>
 * This surface originally stopped at the checkout quote, on the reasoning that
 * an order carries {@code buyerMsisdn} — which payment-service treats as the
 * payer, and which on the EcoCash rail is the handset an unsolicited PIN prompt
 * is delivered to — so an unauthenticated caller who could name that number
 * would hold a phishing tool that works on live phones.
 *
 * <p><b>That reasoning was right about the risk and wrong about this fleet.</b>
 * booking-service already {@code permitAll}s {@code POST /bookings}: a ticket
 * purchase is created with a client-supplied {@code phoneNumber}, and that
 * number is what payment-service later hands EcoCash as the payer. Guest
 * checkout is the fleet's established posture for a super app that
 * authenticates elsewhere, and marketplace refusing it left the ZW app able to
 * fill a basket and unable to buy anything in it. So the order journey lives
 * here now, and the risk is accepted knowingly rather than reasoned away.
 *
 * <p><b>Two things make it narrower than ticketing's, not wider:</b>
 * <ol>
 *   <li><b>The order endpoints require a configured api-key</b>
 *       ({@link #requireOrderRail()}). The pre-checkout endpoints may run
 *       ungated — a leaked cart is a nuisance — but anything that reserves
 *       stock or names a payer is 404 on a cell that has not set
 *       {@code MARKETPLACE_PUBLIC_TEST_API_KEY}. {@code POST /bookings} has no
 *       equivalent gate at all.</li>
 *   <li><b>The payer is still never a free choice of the caller's alone.</b>
 *       Ownership of every order, parcel, dispute and review keys on the
 *       DERIVED buyer id below, so a caller can only ever act on orders their
 *       own handle created.</li>
 * </ol>
 *
 * <p><b>Still absent, and still deliberate:</b> nothing here reaches a seller's
 * or an operator's surface — no dispatch, no delivery marking, no moderation,
 * no settlement, no payout. Those are not blocked by an accident of routing:
 * the derived caller holds {@code CUSTOMER} and nothing else, so the
 * {@code @PreAuthorize} on each of them refuses it.
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

             **The `handle` in the path is the customer's PHONE NUMBER** — the number the broker \
             authenticated at the InnBucks middleware (Veengu). Any spelling normalises to the same \
             buyer (`0771234567` ≡ `+263771234567`), so the same customer gets the same basket on \
             every device with no account or linking step — the loyalty posture. Orders under a \
             phone handle are payable BY that phone; a body `buyerMsisdn` is ignored. A handle \
             containing a letter (`alice`) keys a disposable demo buyer instead, exactly as before, \
             and that one still names its payer in the order body. Handles are hashed and never \
             stored as typed.

             **Optional `x-api-key`.** A cell MAY put a shared key in front of this whole prefix \
             (`MARKETPLACE_PUBLIC_TEST_API_KEY`). Where one is set, every call needs the header and a \
             missing or wrong value is a `401`; where it is blank — the default — no header is needed. \
             Ask which applies to the cell you are pointed at. The key identifies the APP, not the \
             customer.

             **The order journey is here too — browse, cart, address, quote, order, pay, track, \
             confirm, review** — mirroring booking-service's guest checkout, which the super app \
             already buys tickets through. Because ordering names a payer and reserves real stock, \
             **those endpoints additionally require the cell to have an `x-api-key` configured**: \
             on an ungated cell the pre-checkout endpoints work and the order endpoints are `404`.

             `buyerMsisdn` is **required** in the order body here — the derived caller carries no \
             phone claim of its own, so there is nothing to fall back to. It is validated to E.164 \
             and it is the number payment-service will prompt to pay.

             Nothing here reaches a seller or operator surface. The derived caller is a `CUSTOMER` \
             and nothing else.

             When `POST /auth/exchange` goes live the same journey is available with a real fleet \
             token and no api-key — see `Auth-Exchange-Frontend-Integration.md`. The request and \
             response bodies are identical, so switching over is a change of URL and header, not a \
             rewrite.""")
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
    private final OrderService orderService;
    private final FulfilmentService fulfilmentService;
    private final DisputeService disputeService;
    private final ReviewService reviewService;
    private final ParcelTrackingService trackingService;
    private final PublicBuyerResolver buyerResolver;
    private final MarketplaceMetrics metrics;

    /**
     * Master switch. Default {@code false} so the endpoints are absent unless a
     * cell deliberately turns them on — the same fail-closed posture
     * {@code ProductionSecretsGuard} takes for secrets.
     */
    @Value("${marketplace.public-test.enabled:false}")
    private boolean enabled;

    /**
     * Read here only to decide whether the ORDER endpoints are served — the
     * header itself is checked by {@code PublicTestApiKeyFilter}, by shape,
     * for the whole prefix. Duplicating the compare in this class is exactly
     * the mistake that rule exists to prevent.
     */
    @Value("${marketplace.public-test.api-key:}")
    private String apiKey;

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

    // -------------------------------------------------------------- orders

    @PostMapping("/buyers/{handle}/orders")
    @Operation(summary = "[TEST] Place an order",
            description = "Creates a real order: it reserves real merchant stock and it names the "
                    + "phone payment-service will prompt to pay. Same service call, same rules and "
                    + "the same response body as the authenticated `POST /marketplace/orders`.\n\n"
                    + "**`buyerMsisdn` is required here.** On the authenticated surface the payer "
                    + "comes from the token's phone claim and the body field is ignored; the "
                    + "derived caller has no phone claim, so the body is the only source and a "
                    + "missing one is a 400. It is normalised to E.164 and a number that is not "
                    + "dialable is refused rather than stored.\n\n"
                    + "**Requires the cell to have an `x-api-key` configured** — on an ungated "
                    + "cell this endpoint is 404 while the cart still works. **`Idempotency-Key` "
                    + "is required** (400 `idempotency_key_required` without it, checked before "
                    + "anything else): retry the same body under the same key to replay the "
                    + "original response rather than buying twice.\n\n"
                    + "The response carries the `payment` block naming what to POST to "
                    + "payment-service next.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order placed; stock reserved, awaiting payment"),
            @ApiResponse(responseCode = "400", description = "Missing `Idempotency-Key` (checked first), "
                    + "missing or invalid `buyerMsisdn`, or a malformed basket",
                    content = @Content(examples = {
                            @ExampleObject(name = "No idempotency key", value = """
                                    {"code":"idempotency_key_required","message":"Idempotency-Key header is required","data":null}"""),
                            @ExampleObject(name = "No payer number", value = """
                                    {"code":"invalid_msisdn","message":"buyerMsisdn is required","data":null}""")})),
            @ApiResponse(responseCode = "404", description = "The surface is off, or this cell has no api-key configured",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "A line lost the stock race, or the key is in flight"),
            @ApiResponse(responseCode = "422", description = "Lines unavailable or short-stocked; `data.rejections` names every one")
    })
    public ResponseEntity<ApiResult<OrderResponse>> createOrder(
            @PathVariable String handle,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_create");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created(orderService.createOrder(buyer, request, idempotencyKey)));
    }

    @GetMapping("/buyers/{handle}/orders")
    @Operation(summary = "[TEST] This handle's orders",
            description = "Newest first. Only orders this handle placed — ownership is the derived "
                    + "buyer id, so there is no parameter that could name someone else's.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order page"),
            @ApiResponse(responseCode = "404", description = "The surface is off, or this cell has no api-key configured",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<OrderPageResponse>> myOrders(
            @PathVariable String handle,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_list");
        // no-store, as the authenticated twin: the parcels' `actions` change with time.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(
                OrderPageResponse.from(orderService.getMine(buyer, pageable))));
    }

    @GetMapping("/buyers/{handle}/orders/{orderId}")
    @Operation(summary = "[TEST] One order, with its parcels and payment block",
            description = "The tracking screen's single read. Another handle's order is a **404**, "
                    + "the same owner-masked answer the authenticated surface gives.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<OrderResponse>> myOrder(
            @PathVariable String handle, @PathVariable String orderId) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_read");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(
                orderService.getOrder(buyer, parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"))));
    }

    @PostMapping("/buyers/{handle}/orders/{orderId}/cancel")
    @Operation(summary = "[TEST] Cancel an unpaid order",
            description = "Only a PENDING_PAYMENT order can be cancelled; its reserved stock is "
                    + "returned exactly once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled; stock returned"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "The order has moved on and cannot be cancelled")
    })
    public ResponseEntity<ApiResult<OrderResponse>> cancelOrder(
            @PathVariable String handle, @PathVariable String orderId) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_cancel");
        return ResponseEntity.ok(ApiResult.ok("Order cancelled", orderService.cancelOrder(
                buyer, parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"))));
    }

    // ---------------------------------------------------------- after the sale

    @PostMapping("/buyers/{handle}/orders/{orderId}/fulfilments/{fulfilmentId}/received")
    @Operation(summary = "[TEST] Confirm a parcel arrived",
            description = "Closes ONE parcel — a multi-seller order is confirmed a seller at a "
                    + "time, because that is how the goods arrive. A buyer's own confirmation "
                    + "releases that seller's escrow immediately.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parcel closed; the whole order is returned"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order/parcel",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "The parcel is already closed")
    })
    public ResponseEntity<ApiResult<OrderResponse>> confirmReceived(
            @PathVariable String handle, @PathVariable String orderId,
            @PathVariable String fulfilmentId) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_confirm_receipt");
        return ResponseEntity.ok(ApiResult.ok("Thanks - receipt confirmed",
                orderService.confirmReceived(buyer,
                        parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"),
                        parseUuid(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"))));
    }

    @PostMapping("/buyers/{handle}/orders/{orderId}/fulfilments/{fulfilmentId}/cancel")
    @Operation(summary = "[TEST] Cancel a paid parcel before it is sent",
            description = "While the seller is still preparing it (`trackingStatus: RECEIVED`): "
                    + "the stock goes back on sale and the parcel's money is queued for refund. "
                    + "Body `{ \"reason\": \"...\" }` is optional. Afterwards the parcel reads "
                    + "`unfulfilledBy: BUYER`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parcel cancelled; the whole order is returned"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order/parcel",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "Already sent (parcel_not_cancellable) "
                    + "or under dispute (parcel_disputed)")
    })
    public ResponseEntity<ApiResult<OrderResponse>> cancelParcel(
            @PathVariable String handle, @PathVariable String orderId,
            @PathVariable String fulfilmentId,
            @Valid @RequestBody(required = false)
            com.innbucks.marketplaceservice.fulfilment.dto.CancelParcelRequest request) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_cancel_parcel");
        return ResponseEntity.ok(ApiResult.ok("Parcel cancelled - your refund is on its way",
                orderService.cancelParcel(buyer,
                        parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"),
                        parseUuid(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"),
                        request == null ? null : request.reason())));
    }

    @PostMapping("/buyers/{handle}/orders/{orderId}/fulfilments/{fulfilmentId}/collect-code")
    @Operation(summary = "[TEST] Mint a collection handover code",
            description = "COLLECTION parcels only. **The response is the only place the code is "
                    + "ever readable** — no seller surface can see it. Calling again mints a fresh "
                    + "code and kills the previous one.\n\n"
                    + "Note this endpoint SENDS AN SMS to whoever is collecting, so it costs money "
                    + "per call on a live cell.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A live code for this parcel"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order/parcel",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "A delivery order, a parcel already "
                    + "handed over, or a cancelled one - `collect_code_not_applicable` / "
                    + "`illegal_fulfilment_state`, as on the authenticated endpoint")
    })
    public ResponseEntity<ApiResult<CollectCodeResponse>> collectCode(
            @PathVariable String handle, @PathVariable String orderId,
            @PathVariable String fulfilmentId) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_collect_code");
        return ResponseEntity.ok(ApiResult.ok("Collection code ready - show it when you collect",
                fulfilmentService.mintCollectCode(buyer,
                        parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"),
                        parseUuid(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"))));
    }

    @GetMapping("/buyers/{handle}/orders/{orderId}/fulfilments/{fulfilmentId}/tracking")
    @Operation(summary = "[TEST] Track a parcel",
            description = "The buyer's tracking screen for one parcel: RECEIVED / DISPATCHED / "
                    + "DELIVERED / CANCELLED with the time each stage was reached, and the "
                    + "courier's last reported position while a DELIVERY parcel is on the road. "
                    + "Same body as the authenticated `GET /marketplace/orders/{id}/fulfilments/"
                    + "{fulfilmentId}/tracking`. Poll it (every 15-30s is plenty) while the map "
                    + "is open.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The parcel's tracking"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order/parcel",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404)))
    })
    public ResponseEntity<ApiResult<ParcelTrackingResponse>> tracking(
            @PathVariable String handle, @PathVariable String orderId,
            @PathVariable String fulfilmentId) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_tracking");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(trackingService.buyerTracking(buyer,
                        parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"),
                        parseUuid(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"))));
    }

    @PostMapping("/buyers/{handle}/orders/{orderId}/fulfilments/{fulfilmentId}/dispute")
    @Operation(summary = "[TEST] Dispute a parcel",
            description = "Freezes THIS parcel's money until an operator decides. One dispute per "
                    + "parcel, ever. Disputing an undelivered parcel is legal on purpose — \"it "
                    + "never arrived\" is the refund path.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dispute opened"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or not this handle's order/parcel",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "Already disputed, or a refund is already due")
    })
    public ResponseEntity<ApiResult<DisputeResponse>> dispute(
            @PathVariable String handle, @PathVariable String orderId,
            @PathVariable String fulfilmentId, @Valid @RequestBody DisputeRequest request) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "order_dispute");
        return ResponseEntity.ok(ApiResult.ok("Dispute opened - we will review it and get back to you",
                disputeService.open(buyer,
                        parseUuid(orderId, "invalid_order_id", "Order id must be a UUID"),
                        parseUuid(fulfilmentId, "invalid_fulfilment_id", "Fulfilment id must be a UUID"),
                        request.reason(), request.detail())));
    }

    @PostMapping("/buyers/{handle}/listings/{listingId}/reviews")
    @Operation(summary = "[TEST] Review something this handle bought",
            description = "Verified purchase only: the handle needs a PAID order containing this "
                    + "listing, or it is a 403. That gate is the same query the authenticated "
                    + "surface runs, so a handle can only review what it actually paid for.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review posted"),
            @ApiResponse(responseCode = "403", description = "No paid order of this handle contains the listing"),
            @ApiResponse(responseCode = "404", description = "Surface off, cell ungated, or no such listing",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_DISABLED_404))),
            @ApiResponse(responseCode = "409", description = "This handle has already reviewed this listing")
    })
    public ResponseEntity<ApiResult<ReviewResponse>> review(
            @PathVariable String handle, @PathVariable String listingId,
            @Valid @RequestBody ReviewRequest request) {
        AuthenticatedUser buyer = actAsOrderingBuyer(handle, "review_create");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(
                reviewService.create(buyer,
                        parseUuid(listingId, "invalid_listing_id", "Listing id must be a UUID"),
                        request)));
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
        AuthenticatedUser buyer = buyerResolver.resolve(handle);
        metrics.publicTestCall(operation);
        // WARN, not INFO: every line here is an unauthenticated write or read
        // that would normally have required a token, and the point is that it
        // stands out in a log nobody was expecting it in. A phone handle is a
        // real customer's number now, so it is MASKED here (fleet rule: never
        // log a full MSISDN); an opaque demo handle stays readable.
        log.warn("[public-test] {} handle={} buyer={}",
                operation, buyerResolver.loggable(handle), buyer.uuid());
        return buyer;
    }

    /**
     * {@link #actAs} plus the second gate the money-adjacent half of this
     * surface carries. Every endpoint that reserves stock, names a payer,
     * moves escrow or sends an SMS goes through this one and not through
     * {@code actAs} directly.
     */
    private AuthenticatedUser actAsOrderingBuyer(String handle, String operation) {
        requireOrderRail();
        return actAs(handle, operation);
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

    /**
     * The order half of this surface needs the cell to be GATED, not merely
     * enabled.
     *
     * <p>The two halves carry different consequences, so they get different
     * bars. An ungated cart is a nuisance — the worst a stranger does is fill
     * somebody's basket. An ungated order reserves a merchant's real stock and
     * writes a phone number that payment-service will later prompt to pay, and
     * a blank {@code MARKETPLACE_PUBLIC_TEST_API_KEY} is much more often an
     * operator who has not finished provisioning than a deliberate choice to
     * run wide open.
     *
     * <p>So the refusal is 404, matched to {@link #requireEnabled()}: a cell
     * that has not finished provisioning answers exactly as a cell that never
     * enabled the surface, and the boot log — not the wire — is where an
     * operator learns which of the two they are looking at.
     */
    private void requireOrderRail() {
        requireEnabled();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            metrics.publicTestRejected("order_rail_ungated");
            log.warn("[public-test] order endpoint refused: the surface is enabled but "
                    + "MARKETPLACE_PUBLIC_TEST_API_KEY is blank, so ordering stays off");
            throw ApiException.notFound("not_found", "Not found");
        }
    }

    /** GlobalExceptionHandler has no MethodArgumentTypeMismatch mapping, so a
     *  UUID-typed {@code @PathVariable} would 500 on garbage — parse here. */
    private static UUID parseUuid(String raw, String code, String message) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(code, message);
        }
    }
}
