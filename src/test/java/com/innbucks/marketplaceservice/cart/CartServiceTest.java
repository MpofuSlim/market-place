package com.innbucks.marketplaceservice.cart;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.catalog.ListingViewAssembler;
import com.innbucks.marketplaceservice.cart.dto.CartResponse;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.BasketViewAssembler;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.support.TestTowns;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    private CartService service;

    @BeforeEach
    void setUp() {
        cartRepository = mock(CartItemRepository.class);
        listingRepository = mock(ListingRepository.class);
        ListingViewAssembler listingViews = mock(ListingViewAssembler.class);
        when(listingViews.toResponsesById(any())).thenReturn(Map.of());
        service = new CartService(cartRepository, listingRepository,
                new CheckoutPricer(listingRepository, mock(ListingDeliveryTownRepository.class),
                        TestTowns.zimbabwe(), "USD"),
                new BasketViewAssembler(listingViews));
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
}
