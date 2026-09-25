package com.innbucks.marketplaceservice.cart;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.catalog.ListingViewAssembler;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.cart.dto.CartResponse;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.BasketViewAssembler;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.checkout.dto.PricedLineResponse;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.support.TestTowns;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins what the cart is and is not: a set of quantities, priced live, holding
 * no stock and making no promises.
 */
class CartServiceTest {

    private static final int MAX_ITEMS = 3;
    private static final int MAX_QTY = 10;
    private static final UUID LISTING = new UUID(0, 1);
    private static final UUID BUYER_UUID = UUID.randomUUID();
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");

    private CartItemRepository cartRepository;
    private ListingRepository listingRepository;
    private CartVariantItemRepository variantCartRepository;
    private com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository variantRepository;
    private CartService service;

    @BeforeEach
    void setUp() {
        cartRepository = mock(CartItemRepository.class);
        listingRepository = mock(ListingRepository.class);
        variantCartRepository = mock(CartVariantItemRepository.class);
        variantRepository = mock(com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository.class);
        ListingViewAssembler listingViews = mock(ListingViewAssembler.class);
        when(listingViews.toResponsesById(any())).thenReturn(Map.of());
        service = new CartService(cartRepository, listingRepository,
                new CheckoutPricer(listingRepository, mock(ListingDeliveryTownRepository.class),
                        TestTowns.zimbabwe(), "USD", variantRepository),
                new BasketViewAssembler(listingViews),
                variantCartRepository, variantRepository);
        ReflectionTestUtils.setField(service, "maxItems", MAX_ITEMS);
        ReflectionTestUtils.setField(service, "maxQuantityPerItem", MAX_QTY);
        ReflectionTestUtils.setField(service, "currency", "USD");
        when(listingRepository.existsById(any())).thenReturn(true);
    }

    private static Listing sellable(UUID id, long priceCents, int stock) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(id).merchantId(UUID.randomUUID()).title("Solar Lantern 20W")
                .priceCents(priceCents).currency("USD").stockQty(stock)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now).build();
    }

    private static CartItem cartItem(UUID listingId, int qty) {
        Instant now = Instant.now();
        return CartItem.builder().buyerUuid(BUYER_UUID).listingId(listingId)
                .quantity(qty).addedAt(now).updatedAt(now).build();
    }

    private void cartHolds(CartItem... items) {
        when(cartRepository.findByBuyerUuidOrderByAddedAtDesc(BUYER_UUID))
                .thenReturn(List.of(items));
    }

    // ------------------------------------------------------------------
    // Live pricing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The cart prices from the CURRENT listing, never from what it cost when added")
    void pricesLive() {
        cartHolds(cartItem(LISTING, 2));
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(LISTING, 1550, 10)));

        CartResponse cart = service.getCart(BUYER);

        assertThat(cart.subtotalCents()).isEqualTo(3100);
        assertThat(cart.totalQuantity()).isEqualTo(2);
        assertThat(cart.lineCount()).isEqualTo(1);
        assertThat(cart.currency()).isEqualTo("USD");
        assertThat(cart.checkoutReady()).isTrue();
    }

    @Test
    @DisplayName("A line that went out of stock STAYS in the cart, flagged, contributing zero")
    void keepsUnbuyableLinesVisible() {
        cartHolds(cartItem(LISTING, 5));
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(LISTING, 1550, 3)));

        CartResponse cart = service.getCart(BUYER);

        // Dropping it silently is how a shopper reaches checkout with a total
        // they do not recognise.
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().getFirst().issue()).isNotNull();
        assertThat(cart.items().getFirst().issue().reason())
                .isEqualTo(OrderLineRejection.REASON_INSUFFICIENT_STOCK);
        assertThat(cart.items().getFirst().lineTotalCents()).isZero();
        assertThat(cart.subtotalCents()).isZero();
        assertThat(cart.checkoutReady()).isFalse();
    }

    @Test
    @DisplayName("An empty cart is checkout-ready: false, not an error")
    void emptyCartIsNotReady() {
        cartHolds();

        CartResponse cart = service.getCart(BUYER);

        assertThat(cart.items()).isEmpty();
        assertThat(cart.subtotalCents()).isZero();
        assertThat(cart.checkoutReady()).isFalse();
    }

    @Test
    @DisplayName("The cart's addedAt rides each line, so the app can keep a stable order")
    void carriesAddedAt() {
        CartItem item = cartItem(LISTING, 1);
        cartHolds(item);
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(LISTING, 100, 10)));

        assertThat(service.getCart(BUYER).items().getFirst().addedAt())
                .isEqualTo(item.getAddedAt());
    }

    // ------------------------------------------------------------------
    // Adding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Add ACCUMULATES and is capped rather than refused — the + button wants the cap")
    void addAccumulatesAndClamps() {
        cartHolds();

        service.add(BUYER, LISTING, 4);

        verify(cartRepository).addQuantity(eq(BUYER_UUID), eq(LISTING), eq(4), eq(MAX_QTY), any());
    }

    @Test
    @DisplayName("Adding a listing that does not exist is a 404, not a dangling cart row")
    void addRequiresTheListingToExist() {
        when(listingRepository.existsById(LISTING)).thenReturn(false);

        assertThatThrownBy(() -> service.add(BUYER, LISTING, 1))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("listing_not_found");
    }

    @Test
    @DisplayName("An out-of-stock listing can still be added — the cart is not the guard")
    void addAcceptsAnUnbuyableListing() {
        // Same stance favorites take. The cart read carries the issue; checkout
        // is where it is finally refused.
        cartHolds();
        when(cartRepository.existsById(any())).thenReturn(false);
        when(cartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn(0L);

        service.add(BUYER, LISTING, 1);

        verify(cartRepository).addQuantity(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("The cart cannot exceed what an order may carry")
    void cartIsCappedAtTheOrderLineLimit() {
        // Otherwise the shopper fills it and only discovers at checkout that it
        // can never be bought.
        when(cartRepository.existsById(any())).thenReturn(false);
        when(cartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn((long) MAX_ITEMS);

        assertThatThrownBy(() -> service.add(BUYER, LISTING, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).code()).isEqualTo("cart_full");
                    assertThat(ex.getMessage()).contains("maximum of " + MAX_ITEMS);
                });
    }

    @Test
    @DisplayName("Changing a line already in a FULL cart is allowed — it adds no new line")
    void aFullCartStillAllowsEditingWhatIsInIt() {
        cartHolds(cartItem(LISTING, 1));
        when(cartRepository.existsById(any())).thenReturn(true);
        when(cartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn((long) MAX_ITEMS);
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(LISTING, 100, 10)));

        service.add(BUYER, LISTING, 1);

        verify(cartRepository).addQuantity(any(), any(), anyInt(), anyInt(), any());
    }

    // ------------------------------------------------------------------
    // Setting an exact quantity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Set is an exact write — above the cap it is REFUSED, not clamped")
    void setAboveTheCapIsRefused() {
        // The shopper named this number; silently storing a different one would
        // make the stepper disagree with what it shows.
        assertThatThrownBy(() -> service.setQuantity(BUYER, LISTING, MAX_QTY + 1))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).code()).isEqualTo("invalid_quantity");
                    assertThat(ex.getMessage()).contains("up to " + MAX_QTY);
                });
        verify(cartRepository, never()).setQuantity(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Quantity 0 is refused — a zero must never become a silent delete on a retry")
    void zeroQuantityIsRefused() {
        assertThatThrownBy(() -> service.setQuantity(BUYER, LISTING, 0))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("invalid_quantity");
    }

    @Test
    @DisplayName("Set writes the exact quantity asked for")
    void setWritesExactly() {
        cartHolds();
        when(cartRepository.existsById(any())).thenReturn(true);

        service.setQuantity(BUYER, LISTING, 3);

        verify(cartRepository).setQuantity(eq(BUYER_UUID), eq(LISTING), eq(3), any());
    }

    // ------------------------------------------------------------------
    // Removing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Removing a line that is not there is a no-op, so the button can retry blindly")
    void removeIsIdempotent() {
        cartHolds();

        CartResponse cart = service.remove(BUYER, LISTING);

        verify(cartRepository).remove(BUYER_UUID, LISTING);
        assertThat(cart.items()).isEmpty();
    }

    @Test
    @DisplayName("Clearing answers with the empty cart without re-reading it")
    void clearReturnsAnEmptyCart() {
        CartResponse cart = service.clear(BUYER);

        verify(cartRepository).clear(BUYER_UUID);
        assertThat(cart.items()).isEmpty();
        assertThat(cart.checkoutReady()).isFalse();
    }

    @Test
    @DisplayName("Ordering removes ONLY the checked-out lines, never the whole cart")
    void removeOrderedIsScopedToWhatWasBought() {
        service.removeOrdered(BUYER_UUID, List.of(LISTING));

        verify(cartRepository).removeAll(BUYER_UUID, List.of(LISTING));
    }

    @Test
    @DisplayName("Ordering nothing touches nothing")
    void removeOrderedWithNoLinesIsANoOp() {
        service.removeOrdered(BUYER_UUID, List.of());

        verify(cartRepository, never()).removeAll(any(), any());
    }

    // ------------------------------------------------------------------
    // Handing the cart to checkout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("basketOf hands checkout the cart's lines in the shopper's own order")
    void basketOfPreservesCartOrder() {
        UUID other = new UUID(0, 2);
        cartHolds(cartItem(LISTING, 2), cartItem(other, 1));

        assertThat(service.basketOf(BUYER))
                .containsExactly(new BasketLine(LISTING, 2), new BasketLine(other, 1));
    }

    // ------------------------------------------------------------------
    // Product options (V19): option lines live in cart_variant_item
    // ------------------------------------------------------------------

    // The canonical Swagger data: "Cotton Crew Tee" at 1999, Size x Colour.
    private static final UUID TEE = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID M_BLACK = UUID.fromString("0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");
    /** Another listing with options, whose options are NOT the tee's. */
    private static final UUID JACKET = new UUID(0, 7);
    private static final Instant T0 = Instant.parse("2026-09-24T08:00:00Z");

    /** The tee sells options, and its two options are M/Black and XL/Black —
     *  and only the tee's: the variant lookup is keyed on (option, listing). */
    private void teeSellsOptions() {
        when(listingRepository.existsByIdAndHasVariantsTrue(TEE)).thenReturn(true);
        when(listingRepository.existsByIdAndHasVariantsTrue(JACKET)).thenReturn(true);
        when(variantRepository.existsByIdAndListingId(any(), any())).thenAnswer(call ->
                TEE.equals(call.getArgument(1))
                        && Set.of(M_BLACK, XL_BLACK).contains(call.<UUID>getArgument(0)));
    }

    private static Listing tee() {
        Instant now = Instant.now();
        return Listing.builder()
                .id(TEE).merchantId(UUID.randomUUID()).title("Cotton Crew Tee")
                .priceCents(1999).currency("USD").stockQty(10)
                .hasVariants(true).option1Name("Size").option2Name("Colour")
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now).build();
    }

    private static ListingVariant option(UUID id, String size, Long priceOverrideCents, int stock) {
        ListingVariant variant = ListingVariant.builder()
                .id(id).listingId(TEE).priceCents(priceOverrideCents).stockQty(stock)
                .createdAt(T0).updatedAt(T0).version(0L).build();
        variant.setValues(size, "Black");
        return variant;
    }

    private static CartVariantItem optionItem(UUID variantId, int qty, Instant addedAt) {
        return CartVariantItem.builder().buyerUuid(BUYER_UUID).variantId(variantId).listingId(TEE)
                .quantity(qty).addedAt(addedAt).updatedAt(addedAt).build();
    }

    private static CartItem plainItem(UUID listingId, int qty, Instant addedAt) {
        return CartItem.builder().buyerUuid(BUYER_UUID).listingId(listingId)
                .quantity(qty).addedAt(addedAt).updatedAt(addedAt).build();
    }

    private void optionCartHolds(CartVariantItem... items) {
        when(variantCartRepository.findByBuyerUuidOrderByAddedAtDesc(BUYER_UUID))
                .thenReturn(List.of(items));
    }

    private static void refused(Runnable call, HttpStatus status, String code, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(status);
                    assertThat(api.code()).isEqualTo(code);
                    assertThat(api.getMessage()).isEqualTo(message);
                });
    }

    @Test
    @DisplayName("Adding an option writes to the OPTION table, keyed on the option and carrying its listing")
    void addingAnOptionGoesToTheOptionTable() {
        teeSellsOptions();

        service.add(BUYER, TEE, M_BLACK, 2);

        verify(variantCartRepository).addQuantity(eq(BUYER_UUID), eq(M_BLACK), eq(TEE), eq(2),
                eq(MAX_QTY), any());
        // cart_item is never touched by an option line.
        verify(cartRepository, never()).addQuantity(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("An option that does not exist, is another listing's, or is named on a listing without options is ONE 404")
    void unknownOptionIsVariantNotFound() {
        teeSellsOptions();
        UUID missing = new UUID(0, 99);

        // Missing entirely.
        refused(() -> service.add(BUYER, TEE, missing, 1),
                HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found");
        // The tee's real option, named against another listing with options —
        // the same answer, so nothing is disclosed about other listings' ids.
        refused(() -> service.add(BUYER, JACKET, M_BLACK, 1),
                HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found");
        // A listing WITHOUT options has none to name, whatever the id — even
        // an option row that names it: has_variants, not the option table,
        // decides whether a listing sells options.
        UUID stale = new UUID(0, 77);
        when(variantRepository.existsByIdAndListingId(stale, LISTING)).thenReturn(true);
        refused(() -> service.add(BUYER, LISTING, M_BLACK, 1),
                HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found");
        refused(() -> service.add(BUYER, LISTING, stale, 1),
                HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found");
        // PUT resolves the line exactly the same way.
        refused(() -> service.setQuantity(BUYER, TEE, missing, 1),
                HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found");

        verify(variantCartRepository, never())
                .addQuantity(any(), any(), any(), anyInt(), anyInt(), any());
        verify(variantCartRepository, never()).setQuantity(any(), any(), any(), anyInt(), any());
        verify(cartRepository, never()).addQuantity(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("A listing with options refuses a line with none chosen: 400 variant_required, nothing written")
    void listingWithOptionsRequiresOne() {
        teeSellsOptions();
        String message = "Choose an option before adding this item to your cart";

        refused(() -> service.add(BUYER, TEE, null, 1),
                HttpStatus.BAD_REQUEST, "variant_required", message);
        // The pre-V19 call an old app makes gets the same honest answer.
        refused(() -> service.add(BUYER, TEE, 1),
                HttpStatus.BAD_REQUEST, "variant_required", message);
        refused(() -> service.setQuantity(BUYER, TEE, 1),
                HttpStatus.BAD_REQUEST, "variant_required", message);

        verify(cartRepository, never()).addQuantity(any(), any(), anyInt(), anyInt(), any());
        verify(cartRepository, never()).setQuantity(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("A missing listing is still listing_not_found, checked before any option")
    void missingListingWinsOverAnOption() {
        when(listingRepository.existsById(TEE)).thenReturn(false);

        refused(() -> service.add(BUYER, TEE, M_BLACK, 1),
                HttpStatus.NOT_FOUND, "listing_not_found", "Listing not found");
    }

    @Test
    @DisplayName("Two sizes of one listing are TWO lines, each with its own quantity, addedAt and price")
    void twoSizesAreTwoLines() {
        Instant later = T0.plusSeconds(60);
        optionCartHolds(optionItem(M_BLACK, 2, later), optionItem(XL_BLACK, 1, T0));
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any()))
                .thenReturn(List.of(option(M_BLACK, "M", null, 4), option(XL_BLACK, "XL", 2299L, 6)));

        CartResponse cart = service.getCart(BUYER);

        assertThat(cart.lineCount()).isEqualTo(2);
        assertThat(cart.totalQuantity()).isEqualTo(3);
        assertThat(cart.items()).extracting(PricedLineResponse::variantId)
                .containsExactly(M_BLACK, XL_BLACK);
        assertThat(cart.items()).extracting(PricedLineResponse::addedAt)
                .containsExactly(later, T0);
        assertThat(cart.items()).extracting(PricedLineResponse::lineTotalCents)
                .containsExactly(2 * 1999L, 2299L);
        assertThat(cart.subtotalCents()).isEqualTo(2 * 1999 + 2299);
        assertThat(cart.checkoutReady()).isTrue();
    }

    @Test
    @DisplayName("Set on an option line writes that exact line, and above the cap it is refused per line")
    void setOnAnOptionLine() {
        teeSellsOptions();
        when(variantCartRepository.existsById(new CartVariantItemId(BUYER_UUID, M_BLACK)))
                .thenReturn(true);

        service.setQuantity(BUYER, TEE, M_BLACK, 3);

        verify(variantCartRepository).setQuantity(eq(BUYER_UUID), eq(M_BLACK), eq(TEE), eq(3), any());
        verify(cartRepository, never()).setQuantity(any(), any(), anyInt(), any());

        refused(() -> service.setQuantity(BUYER, TEE, XL_BLACK, MAX_QTY + 1),
                HttpStatus.BAD_REQUEST, "invalid_quantity",
                "You can buy up to " + MAX_QTY + " of one item per order");
        verify(variantCartRepository, never())
                .setQuantity(any(), eq(XL_BLACK), any(), anyInt(), any());
    }

    @Test
    @DisplayName("cart_full counts LINES across both tables — two sizes are two of the cap")
    void cartFullCountsBothTables() {
        teeSellsOptions();
        // One plain line + two option lines = MAX_ITEMS (3).
        when(cartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn(1L);
        when(variantCartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn(2L);
        String message = "Your cart holds the maximum of " + MAX_ITEMS
                + " different items. Remove one, or check out what you have.";

        refused(() -> service.add(BUYER, LISTING, 1), HttpStatus.CONFLICT, "cart_full", message);
        refused(() -> service.add(BUYER, TEE, M_BLACK, 1), HttpStatus.CONFLICT, "cart_full", message);

        verify(cartRepository, never()).addQuantity(any(), any(), anyInt(), anyInt(), any());
        verify(variantCartRepository, never())
                .addQuantity(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("An option line already in a FULL cart can still be topped up — it adds no new line")
    void aFullCartStillAllowsEditingAnOptionLine() {
        teeSellsOptions();
        when(cartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn(1L);
        when(variantCartRepository.countByBuyerUuid(BUYER_UUID)).thenReturn(2L);
        when(variantCartRepository.existsById(new CartVariantItemId(BUYER_UUID, M_BLACK)))
                .thenReturn(true);

        service.add(BUYER, TEE, M_BLACK, 1);

        verify(variantCartRepository).addQuantity(eq(BUYER_UUID), eq(M_BLACK), eq(TEE), eq(1),
                eq(MAX_QTY), any());
    }

    @Test
    @DisplayName("DELETE without an option keeps its pre-V19 meaning: the plain line AND every option line of the listing")
    void removeWithoutAnOptionRemovesTheWholeItem() {
        service.remove(BUYER, TEE);

        verify(cartRepository).remove(BUYER_UUID, TEE);
        verify(variantCartRepository).removeAllOfListing(BUYER_UUID, TEE);
        verify(variantCartRepository, never()).remove(any(), any());
    }

    @Test
    @DisplayName("DELETE with an option removes exactly that line, unvalidated so it can clear a removed option")
    void removeWithAnOptionRemovesOneLine() {
        // Even for a listing that no longer exists: removal is idempotent and
        // never validates, or a line on a deleted option could never be cleared.
        when(listingRepository.existsById(TEE)).thenReturn(false);

        CartResponse cart = service.remove(BUYER, TEE, M_BLACK);

        verify(variantCartRepository).remove(BUYER_UUID, M_BLACK);
        verify(variantCartRepository, never()).removeAllOfListing(any(), any());
        verify(cartRepository, never()).remove(any(), any());
        assertThat(cart.items()).isEmpty();
    }

    @Test
    @DisplayName("Clearing the cart clears BOTH tables")
    void clearClearsBothTables() {
        CartResponse cart = service.clear(BUYER);

        verify(cartRepository).clear(BUYER_UUID);
        verify(variantCartRepository).clear(BUYER_UUID);
        assertThat(cart.items()).isEmpty();
    }

    @Test
    @DisplayName("Ordering removes exactly the option lines it took, from the option table only")
    void removeOrderedVariantsIsScoped() {
        service.removeOrderedVariants(BUYER_UUID, List.of(M_BLACK));

        verify(variantCartRepository).removeAll(BUYER_UUID, List.of(M_BLACK));
        verify(cartRepository, never()).removeAll(any(), any());
    }

    @Test
    @DisplayName("Ordering no option lines touches nothing")
    void removeOrderedVariantsWithNoLinesIsANoOp() {
        service.removeOrderedVariants(BUYER_UUID, List.of());

        verify(variantCartRepository, never()).removeAll(any(), any());
    }

    @Test
    @DisplayName("When only one table has lines its repository order is kept verbatim")
    void singleTableKeepsItsOwnOrder() {
        UUID other = new UUID(0, 2);
        // Deliberately NOT newest-first: the service must not re-sort a cart
        // that has lines in one table only, so a cart without options reads
        // exactly as it did before V19.
        cartHolds(plainItem(LISTING, 1, T0), plainItem(other, 2, T0.plusSeconds(60)));
        assertThat(service.basketOf(BUYER))
                .containsExactly(new BasketLine(LISTING, 1), new BasketLine(other, 2));

        cartHolds();
        optionCartHolds(optionItem(XL_BLACK, 1, T0), optionItem(M_BLACK, 2, T0.plusSeconds(60)));
        assertThat(service.basketOf(BUYER))
                .containsExactly(new BasketLine(TEE, 1, XL_BLACK), new BasketLine(TEE, 2, M_BLACK));
    }

    @Test
    @DisplayName("Lines from both tables are merged newest-first by addedAt")
    void bothTablesMergeByAddedAtNewestFirst() {
        UUID other = new UUID(0, 2);
        cartHolds(plainItem(LISTING, 1, T0.plusSeconds(120)), plainItem(other, 1, T0));
        optionCartHolds(optionItem(XL_BLACK, 1, T0.plusSeconds(180)),
                optionItem(M_BLACK, 2, T0.plusSeconds(60)));

        assertThat(service.basketOf(BUYER)).containsExactly(
                new BasketLine(TEE, 1, XL_BLACK),     // +180s
                new BasketLine(LISTING, 1),           // +120s
                new BasketLine(TEE, 2, M_BLACK),      // +60s
                new BasketLine(other, 1));            // +0s
    }

    @Test
    @DisplayName("A rendered option line carries its variantId, the option as it is now, and its OWN unit price")
    void renderedOptionLineCarriesTheOption() {
        optionCartHolds(optionItem(XL_BLACK, 2, T0));
        cartHolds(plainItem(LISTING, 1, T0.minusSeconds(60)));
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee(), sellable(LISTING, 450, 10)));
        when(variantRepository.findAllById(any())).thenReturn(List.of(option(XL_BLACK, "XL", 2299L, 6)));

        CartResponse cart = service.getCart(BUYER);

        // The listing summary is stubbed out in this class (the mocked
        // ListingViewAssembler resolves nothing); the option block and the unit
        // price come from the priced line, not from that summary.
        PricedLineResponse line = cart.items().getFirst();
        assertThat(line.listingId()).isEqualTo(TEE);
        assertThat(line.variantId()).isEqualTo(XL_BLACK);
        assertThat(line.variant()).isNotNull();
        assertThat(line.variant().id()).isEqualTo(XL_BLACK);
        assertThat(line.variant().values()).containsExactly("XL", "Black");
        assertThat(line.variant().label()).isEqualTo("XL - Black");
        assertThat(line.variant().priceCents()).isEqualTo(2299);
        assertThat(line.variant().priceOverrideCents()).isEqualTo(2299L);
        assertThat(line.variant().stockQty()).isEqualTo(6);
        // listing.priceCents is only the "from" price (1999); the line's own
        // unit price is the option's.
        assertThat(line.unitPriceCents()).isEqualTo(2299L);
        assertThat(line.lineTotalCents()).isEqualTo(4598);
        assertThat(line.addedAt()).isEqualTo(T0);
        assertThat(line.issue()).isNull();

        PricedLineResponse plain = cart.items().get(1);
        assertThat(plain.variantId()).isNull();
        assertThat(plain.variant()).isNull();
        assertThat(plain.unitPriceCents()).isEqualTo(450L);
        assertThat(cart.subtotalCents()).isEqualTo(4598 + 450);
    }

    @Test
    @DisplayName("A line whose option was removed stays visible and removable: variantId echoed, no option block, VARIANT_UNAVAILABLE")
    void removedOptionLineStaysVisible() {
        UUID removed = new UUID(0, 99);
        optionCartHolds(optionItem(removed, 1, T0));
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of());

        CartResponse cart = service.getCart(BUYER);

        PricedLineResponse line = cart.items().getFirst();
        assertThat(line.variantId()).isEqualTo(removed);
        assertThat(line.variant()).isNull();
        assertThat(line.unitPriceCents()).isEqualTo(1999L);
        assertThat(line.lineTotalCents()).isZero();
        assertThat(line.issue().reason()).isEqualTo(OrderLineRejection.REASON_VARIANT_UNAVAILABLE);
        assertThat(line.addedAt()).isEqualTo(T0);
        assertThat(cart.checkoutReady()).isFalse();
    }
}
