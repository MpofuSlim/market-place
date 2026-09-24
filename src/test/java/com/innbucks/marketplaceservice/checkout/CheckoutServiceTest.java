package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTown;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteRequest;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteResponse;
import com.innbucks.marketplaceservice.checkout.dto.PaymentOption;
import com.innbucks.marketplaceservice.delivery.DeliveryAddress;
import com.innbucks.marketplaceservice.delivery.DeliveryAddressService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.support.TestTowns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the decisions the quote and order creation SHARE — where the lines come
 * from, which delivery method applies, what it costs and which rails may pay
 * for it. Everything here is called by both, so a divergence would show up as a
 * quote saying one total and the order charging another.
 */
class CheckoutServiceTest {

    private static final UUID LISTING = new UUID(0, 1);
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");

    private ListingRepository listingRepository;
    private ListingDeliveryTownRepository coverage;
    private CartService cartService;
    private DeliveryAddressService addressService;
    private CheckoutProperties properties;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        coverage = mock(ListingDeliveryTownRepository.class);
        cartService = mock(CartService.class);
        addressService = mock(DeliveryAddressService.class);
        properties = new CheckoutProperties();
        service = new CheckoutService(properties,
                new CheckoutPricer(listingRepository, coverage, TestTowns.zimbabwe(), "USD"),
                mock(BasketViewAssembler.class), cartService, addressService,
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointResolver.class),
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class), "USD");
    }

    private static final UUID SELLER = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");

    private static Listing sellable(long priceCents, int stock) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(LISTING).merchantId(SELLER).title("Solar Lantern 20W")
                .priceCents(priceCents).currency("USD").stockQty(stock)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now).build();
    }

    private static DeliveryAddress address() {
        Instant now = Instant.now();
        return DeliveryAddress.builder()
                .id(UUID.randomUUID()).buyerUuid(UUID.randomUUID()).label("Home")
                .recipientName("Tariro Moyo").recipientMsisdn("+263771234567")
                .line1("14 Samora Machel Ave").city("Harare").townCode("harare")
                .defaultAddress(true).createdAt(now).updatedAt(now).version(0L).build();
    }

    private CheckoutQuoteRequest quoteOf(DeliveryMethod method) {
        return new CheckoutQuoteRequest(null,
                List.of(new CheckoutQuoteRequest.Item(LISTING, 2)), method, null);
    }

    // ------------------------------------------------------------------
    // Basket source
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sending BOTH a cart flag and items is refused, never silently resolved")
    void bothSourcesRefused() {
        // A client that believes it sent a Buy Now and got the whole cart has
        // bought things the shopper never confirmed.
        assertThatThrownBy(() -> service.resolveBasket(BUYER, true,
                List.of(new BasketLine(LISTING, 1))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("ambiguous_basket");
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("Sending NEITHER is refused too")
    void neitherSourceRefused() {
        assertThatThrownBy(() -> service.resolveBasket(BUYER, false, List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("invalid_items");
    }

    @Test
    @DisplayName("An empty cart is its own refusal, not an empty order")
    void emptyCartRefused() {
        when(cartService.basketOf(BUYER)).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveBasket(BUYER, true, List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("cart_empty");
    }

    @Test
    @DisplayName("fromCart takes the cart's lines verbatim")
    void fromCartUsesTheCart() {
        when(cartService.basketOf(BUYER)).thenReturn(List.of(new BasketLine(LISTING, 3)));

        assertThat(service.resolveBasket(BUYER, true, List.of()))
                .containsExactly(new BasketLine(LISTING, 3));
    }

    // ------------------------------------------------------------------
    // Delivery method
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An unstated method is COLLECTION — what an order meant before delivery existed")
    void defaultsToCollection() {
        // Load-bearing for back-compatibility: a client that has not been
        // updated sends no method, and must not start needing an address.
        assertThat(service.resolveMethod(null)).isEqualTo(DeliveryMethod.COLLECTION);
    }

    @Test
    @DisplayName("A cell that offers only DELIVERY defaults to it rather than to nothing")
    void defaultsToTheOnlyOfferedMethod() {
        properties.getDelivery().setMethods(EnumSet.of(DeliveryMethod.DELIVERY));

        assertThat(service.resolveMethod(null)).isEqualTo(DeliveryMethod.DELIVERY);
    }

    @Test
    @DisplayName("A method the cell does not offer is 422, not silently swapped")
    void unofferedMethodRefused() {
        properties.getDelivery().setMethods(EnumSet.of(DeliveryMethod.COLLECTION));

        assertThatThrownBy(() -> service.resolveMethod(DeliveryMethod.DELIVERY))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("delivery_method_unavailable");
    }

    @Test
    @DisplayName("A cell offering NO method is a deployment fault, reported as one")
    void noMethodsIsAConfigurationFault() {
        properties.getDelivery().setMethods(EnumSet.noneOf(DeliveryMethod.class));

        assertThatThrownBy(() -> service.resolveMethod(null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("delivery_unavailable");
    }

    // ------------------------------------------------------------------
    // Payment options
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Only the InnBucks code rail is advertised by default — the fail-safe answer")
    void defaultsToTheOneRailEveryCellHas() {
        // Advertising a rail the cell cannot collect on sends the buyer down a
        // path that dead-ends at payment-service's 503.
        assertThat(service.paymentOptions()).extracting(PaymentOption::rail)
                .containsExactly(PaymentRail.INNBUCKS_CODE);
    }

    @Test
    @DisplayName("Configured rails are advertised in order, with their copy, duplicates collapsed")
    void advertisesConfiguredRailsInOrder() {
        properties.getCheckout().setPaymentMethods(List.of(PaymentRail.ECOCASH,
                PaymentRail.INNBUCKS_CODE, PaymentRail.ECOCASH));

        assertThat(service.paymentOptions()).extracting(PaymentOption::rail)
                .containsExactly(PaymentRail.ECOCASH, PaymentRail.INNBUCKS_CODE);
        assertThat(service.paymentOptions().getFirst().completion())
                .isEqualTo(PaymentOption.COMPLETION_PHONE_PROMPT);
    }

    @Test
    @DisplayName("The payment instruction names the payments service's own addressing, not ours")
    void paymentInstructionCarriesTheForeignCall() {
        Instant payBefore = Instant.now().plusSeconds(1800);

        var instruction = service.paymentInstruction("MKT-4F9A1C22B7D3", 3750, "USD", payBefore);

        assertThat(instruction.endpoint()).isEqualTo("POST /payments");
        // MARKETPLACE + the order REF — not the order's id, which the payments
        // service cannot resolve.
        assertThat(instruction.orderType()).isEqualTo("MARKETPLACE");
        assertThat(instruction.orderRef()).isEqualTo("MKT-4F9A1C22B7D3");
        assertThat(instruction.amountCents()).isEqualTo(3750);
        assertThat(instruction.payBefore()).isEqualTo(payBefore);
        assertThat(instruction.methods()).isEqualTo(service.paymentOptions());
    }

    @Test
    @DisplayName("The cell's options report the same configuration the quote and order read")
    void optionsMirrorTheSameConfiguration() {
        properties.getCheckout().setPaymentMethods(List.of(PaymentRail.ZIMSWITCH_CARD));

        var options = service.options();

        // Deprecated since fees became per seller and per town (V14): always 0.
        assertThat(options.deliveryFeeCents()).isZero();
        assertThat(options.currency()).isEqualTo("USD");
        assertThat(options.deliveryMethods())
                .containsExactlyInAnyOrder(DeliveryMethod.DELIVERY, DeliveryMethod.COLLECTION);
        assertThat(options.paymentMethods()).extracting(PaymentOption::rail)
                .containsExactly(PaymentRail.ZIMSWITCH_CARD);
        assertThat(options.paymentEndpoint()).isEqualTo("POST /payments");
        assertThat(options.paymentOrderType()).isEqualTo("MARKETPLACE");
    }

    // ------------------------------------------------------------------
    // Quote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A DELIVERY quote totals subtotal + the seller's fee to the town, and shows the destination")
    void deliveryQuoteTotalsAndAddresses() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(LISTING, "harare", 200)));
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.DELIVERY));

        assertThat(quote.subtotalCents()).isEqualTo(3100);
        assertThat(quote.deliveryFeeCents()).isEqualTo(200);
        assertThat(quote.totalCents()).isEqualTo(3300);
        assertThat(quote.deliveryFees()).containsExactly(
                new CheckoutQuoteResponse.SellerDeliveryFee(SELLER, 200));
        assertThat(quote.deliveryAddress()).isNotNull();
        assertThat(quote.deliveryAddress().city()).isEqualTo("Harare");
        assertThat(quote.checkoutReady()).isTrue();
        assertThat(quote.rejections()).isNull();
    }

    @Test
    @DisplayName("A COLLECTION quote resolves no address and adds no fee")
    void collectionQuoteHasNoDestinationAndNoFee() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION));

        assertThat(quote.deliveryFeeCents()).isZero();
        assertThat(quote.totalCents()).isEqualTo(3100);
        assertThat(quote.deliveryAddress()).isNull();
        verifyNoInteractions(addressService);
    }

    @Test
    @DisplayName("A basket with a problem still QUOTES — the shopper has to see it to fix it")
    void unbuyableBasketStillQuotes() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 1)));

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION));

        assertThat(quote.checkoutReady()).isFalse();
        assertThat(quote.rejections()).hasSize(1);
        assertThat(quote.subtotalCents()).isZero();
        // Still tells them how they could pay: the basket is fixable, and
        // blanking the picker would make the screen look broken rather than
        // correctable.
        assertThat(quote.paymentMethods()).isNotEmpty();
    }

    @Test
    @DisplayName("A DELIVERY quote for a buyer with no saved address is refused, not quoted blind")
    void deliveryWithNoAddressRefused() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(addressService.requireForCheckout(any(), any()))
                .thenThrow(ApiException.badRequest("delivery_address_required",
                        "Choose a delivery address, or add one first"));

        assertThatThrownBy(() -> service.quote(BUYER, quoteOf(DeliveryMethod.DELIVERY)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("delivery_address_required");
    }

    @Test
    @DisplayName("An address with no town (pre-V14, city matched nothing) is refused for delivery")
    void addressWithoutTownRefusedForDelivery() {
        DeliveryAddress legacy = address();
        legacy.setTownCode(null);
        legacy.setCity("Harare CBD");
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(addressService.requireForCheckout(any(), any())).thenReturn(legacy);

        assertThatThrownBy(() -> service.quote(BUYER, quoteOf(DeliveryMethod.DELIVERY)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("address_town_required");
    }

    @Test
    @DisplayName("A seller who does not deliver to the town still QUOTES, with the line named")
    void uncoveredTownStillQuotes() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(LISTING, "bulawayo", 900)));
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.DELIVERY));

        assertThat(quote.checkoutReady()).isFalse();
        assertThat(quote.rejections()).singleElement()
                .satisfies(r -> {
                    assertThat(r.reason()).isEqualTo("NOT_DELIVERED_TO_TOWN");
                    assertThat(r.message()).isEqualTo("Solar Lantern 20W is not delivered to Harare");
                });
        assertThat(quote.deliveryFeeCents()).isZero();
        assertThat(quote.deliveryFees()).isEmpty();
    }
}
