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
import com.innbucks.marketplaceservice.pickup.CollectionPointResolver;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import com.innbucks.marketplaceservice.support.TestTowns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private MarketplaceSellerRepository sellerRepository;
    private CollectionPointResolver collectionPoints;
    private CollectionPointViews collectionPointViews;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        coverage = mock(ListingDeliveryTownRepository.class);
        cartService = mock(CartService.class);
        addressService = mock(DeliveryAddressService.class);
        properties = new CheckoutProperties();
        sellerRepository = mock(MarketplaceSellerRepository.class);
        collectionPoints = mock(CollectionPointResolver.class);
        collectionPointViews = mock(CollectionPointViews.class);
        service = new CheckoutService(properties,
                new CheckoutPricer(listingRepository, coverage, TestTowns.zimbabwe(), "USD",
                        mock(com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository.class),
                        sellerRepository, properties),
                mock(BasketViewAssembler.class), cartService, addressService,
                collectionPoints, collectionPointViews, "USD");
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
        // The destination is never resolved - strictly - for a collection. The
        // only address read is the lenient one that says where the sellers
        // COULD deliver (V20).
        verify(addressService, never()).requireForCheckout(any(), any());
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

    // ------------------------------------------------------------------
    // Delivery-only sellers + per-seller availability (V20)
    // ------------------------------------------------------------------

    private static final UUID OTHER_LISTING = new UUID(0, 2);
    private static final UUID OTHER_SELLER = UUID.fromString("4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d");

    private static Listing sellableBy(UUID id, UUID merchantId, String title, long priceCents) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(id).merchantId(merchantId).title(title)
                .priceCents(priceCents).currency("USD").stockQty(10)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now).build();
    }

    private CheckoutQuoteRequest twoSellers(DeliveryMethod method, UUID addressId) {
        return new CheckoutQuoteRequest(null, List.of(new CheckoutQuoteRequest.Item(LISTING, 1),
                new CheckoutQuoteRequest.Item(OTHER_LISTING, 1)), method, addressId);
    }

    @Test
    @DisplayName("A DELIVERY quote lists each seller's method, fee and what else they could do, judged at the address's town")
    void deliveryQuoteListsSellers() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10),
                sellableBy(OTHER_LISTING, OTHER_SELLER, "Wireless Earbuds", 2599)));
        when(coverage.findByListingIdIn(any())).thenReturn(List.of(
                new ListingDeliveryTown(LISTING, "harare", 200),
                new ListingDeliveryTown(OTHER_LISTING, "harare", 500)));
        when(sellerRepository.findCollectionDisabledAmong(any())).thenReturn(Set.of(OTHER_SELLER));
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());

        CheckoutQuoteResponse quote = service.quote(BUYER, twoSellers(DeliveryMethod.DELIVERY, null));

        assertThat(quote.checkoutReady()).isTrue();
        assertThat(quote.availabilityTownCode()).isEqualTo("harare");
        assertThat(quote.sellers()).containsExactly(
                new CheckoutQuoteResponse.QuoteSeller(SELLER, DeliveryMethod.DELIVERY,
                        List.of(DeliveryMethod.DELIVERY, DeliveryMethod.COLLECTION), 200L),
                new CheckoutQuoteResponse.QuoteSeller(OTHER_SELLER, DeliveryMethod.DELIVERY,
                        List.of(DeliveryMethod.DELIVERY), 500L));
        // The pre-V20 keys say the same thing they always did.
        assertThat(quote.deliveryFees()).containsExactly(
                new CheckoutQuoteResponse.SellerDeliveryFee(SELLER, 200),
                new CheckoutQuoteResponse.SellerDeliveryFee(OTHER_SELLER, 500));
        assertThat(quote.collectionPoints()).isNull();
        // Only the strict lookup: a destination was needed.
        verify(addressService, never()).findForQuote(any(), any());
    }

    @Test
    @DisplayName("A COLLECTION quote judges DELIVERY availability at the buyer's default address, and still resolves no destination")
    void collectionQuoteJudgesAtTheDefaultAddress() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(LISTING, "harare", 200)));
        when(addressService.findForQuote(BUYER, null)).thenReturn(Optional.of(address()));

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION));

        assertThat(quote.availabilityTownCode()).isEqualTo("harare");
        assertThat(quote.sellers()).containsExactly(new CheckoutQuoteResponse.QuoteSeller(SELLER,
                DeliveryMethod.COLLECTION,
                List.of(DeliveryMethod.DELIVERY, DeliveryMethod.COLLECTION), null));
        // The address is only a yardstick: nothing is priced or shown against it.
        assertThat(quote.deliveryAddress()).isNull();
        assertThat(quote.deliveryFeeCents()).isZero();
        assertThat(quote.deliveryMethod()).isEqualTo(DeliveryMethod.COLLECTION);
        verify(addressService, never()).requireForCheckout(any(), any());
    }

    @Test
    @DisplayName("The lenient lookup never refuses a COLLECTION quote - a stale address id or an empty book just means no town")
    void lenientLookupNeverThrows() {
        // The REAL address service over an empty book: the strict lookup would
        // 404 the stale id and 400 the missing default.
        var addresses = mock(com.innbucks.marketplaceservice.delivery.DeliveryAddressRepository.class);
        service = new CheckoutService(properties,
                new CheckoutPricer(listingRepository, coverage, TestTowns.zimbabwe(), "USD",
                        mock(com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository.class),
                        sellerRepository, properties),
                mock(BasketViewAssembler.class), cartService,
                new DeliveryAddressService(addresses,
                        new com.innbucks.marketplaceservice.api.Msisdns("ZW"), properties,
                        TestTowns.zimbabwe()),
                collectionPoints, collectionPointViews, "USD");
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(LISTING, "bulawayo", 900)));

        UUID stale = UUID.randomUUID();
        CheckoutQuoteResponse named = service.quote(BUYER, new CheckoutQuoteRequest(null,
                List.of(new CheckoutQuoteRequest.Item(LISTING, 2)), DeliveryMethod.COLLECTION, stale));
        CheckoutQuoteResponse unnamed = service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION));

        for (CheckoutQuoteResponse quote : List.of(named, unnamed)) {
            assertThat(quote.checkoutReady()).isTrue();
            assertThat(quote.availabilityTownCode()).isNull();
            // No town known: "delivers somewhere" is enough.
            assertThat(quote.sellers().getFirst().availableMethods())
                    .containsExactly(DeliveryMethod.DELIVERY, DeliveryMethod.COLLECTION);
        }
        // A stale id is NOT swapped for the default.
        verify(addresses).findByIdAndBuyerUuid(eq(stale), any());
        verify(addresses, times(1)).findByBuyerUuidAndDefaultAddressTrue(any());

        // A principal whose uuid is not a UUID (a token with no userUuid claim
        // falls back to its sub) owns no address: the COLLECTION quote still
        // answers, with no town, instead of a 500 from UUID.fromString.
        AuthenticatedUser subOnly = new AuthenticatedUser(
                "tariro@example.com", Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");
        CheckoutQuoteResponse legacy = service.quote(subOnly, quoteOf(DeliveryMethod.COLLECTION));
        assertThat(legacy.checkoutReady()).isTrue();
        assertThat(legacy.availabilityTownCode()).isNull();
        verify(addresses, times(1)).findByBuyerUuidAndDefaultAddressTrue(any());
    }

    @Test
    @DisplayName("An address saved before towns existed gives a COLLECTION quote no town, not an error")
    void townlessDefaultAddressIsNoTown() {
        DeliveryAddress legacy = address();
        legacy.setTownCode(null);
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(addressService.findForQuote(any(), any())).thenReturn(Optional.of(legacy));

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION));

        assertThat(quote.checkoutReady()).isTrue();
        assertThat(quote.availabilityTownCode()).isNull();
    }

    @Test
    @DisplayName("An unstated method stays COLLECTION for the whole basket, even with a delivery-only seller in it")
    void unstatedMethodStaysUniformCollection() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(LISTING, "harare", 200)));
        when(sellerRepository.findCollectionDisabledAmong(any())).thenReturn(Set.of(SELLER));

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(null));

        // Never switched to DELIVERY behind the shopper's back: delivery is an
        // explicit choice. The line says why, and availableMethods says what to do.
        assertThat(quote.deliveryMethod()).isEqualTo(DeliveryMethod.COLLECTION);
        assertThat(quote.checkoutReady()).isFalse();
        assertThat(quote.rejections()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo("COLLECTION_NOT_OFFERED");
            assertThat(r.message()).isEqualTo("Solar Lantern 20W is delivery only");
            assertThat(r.merchantId()).isEqualTo(SELLER);
        });
        assertThat(quote.sellers().getFirst().availableMethods())
                .containsExactly(DeliveryMethod.DELIVERY);
    }

    @Test
    @DisplayName("collectionPoints covers only sellers who collect: a delivery-only seller is never 'arrange collection with the seller'")
    void collectionPointsExcludeDeliveryOnlySellers() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10),
                sellableBy(OTHER_LISTING, OTHER_SELLER, "Wireless Earbuds", 2599)));
        when(sellerRepository.findCollectionDisabledAmong(any())).thenReturn(Set.of(OTHER_SELLER));

        CheckoutQuoteResponse quote = service.quote(BUYER, twoSellers(DeliveryMethod.COLLECTION, null));

        // Only the collecting seller is resolved and rendered.
        verify(collectionPoints).resolve(List.of(SELLER), null);
        assertThat(quote.collectionPoints()).extracting(
                com.innbucks.marketplaceservice.pickup.dto.SellerCollectionPoint::merchantId)
                .containsExactly(SELLER);
        assertThat(quote.rejections()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo("COLLECTION_NOT_OFFERED");
            assertThat(r.listingId()).isEqualTo(OTHER_LISTING);
            assertThat(r.merchantId()).isEqualTo(OTHER_SELLER);
        });
        assertThat(quote.sellers()).extracting(CheckoutQuoteResponse.QuoteSeller::merchantId)
                .containsExactly(SELLER, OTHER_SELLER);
    }

    @Test
    @DisplayName("A basket whose every seller is delivery-only still carries an (empty) collectionPoints on a COLLECTION quote")
    void allDeliveryOnlyCollectionQuoteListsNoPoints() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(sellerRepository.findCollectionDisabledAmong(any())).thenReturn(Set.of(SELLER));

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION));

        assertThat(quote.collectionPoints()).isEmpty();
        verifyNoInteractions(collectionPoints);
    }

    @Test
    @DisplayName("A uniform quote's JSON is the pre-V20 bytes with sellers and availabilityTownCode appended")
    void uniformQuoteJsonIsThePreV20BytesPlusTheNewKeys() throws Exception {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(LISTING, "harare", 200)));
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        CheckoutQuoteResponse quote = service.quote(BUYER, quoteOf(DeliveryMethod.DELIVERY));
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(quote);

        List<String> keys = new java.util.ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        // Every pre-V20 key, in its pre-V20 place; the new keys strictly after.
        assertThat(keys).containsExactly("items", "lineCount", "totalQuantity", "subtotalCents",
                "deliveryFeeCents", "totalCents", "currency", "deliveryMethod", "deliveryMethods",
                "deliveryAddress", "checkoutReady", "paymentMethods", "deliveryFees",
                "sellers", "availabilityTownCode");
        assertThat(mapper.writeValueAsString(json.get("sellers"))).isEqualTo(
                "[{\"merchantId\":\"" + SELLER + "\",\"deliveryMethod\":\"DELIVERY\","
                        + "\"availableMethods\":[\"DELIVERY\",\"COLLECTION\"],\"deliveryFeeCents\":200}]");
        assertThat(json.get("availabilityTownCode").asText()).isEqualTo("harare");
        // A collecting seller's entry has no fee key at all.
        when(addressService.findForQuote(any(), any())).thenReturn(Optional.empty());
        var collected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(
                service.quote(BUYER, quoteOf(DeliveryMethod.COLLECTION)));
        assertThat(collected.get("sellers").get(0).has("deliveryFeeCents")).isFalse();
        assertThat(collected.has("availabilityTownCode")).isFalse();
    }

    // ------------------------------------------------------------------
    // A method per seller (V20, sellerDeliveryMethods)
    // ------------------------------------------------------------------

    private static final UUID STALE_SELLER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    private CheckoutQuoteRequest perSeller(DeliveryMethod basketDefault, UUID addressId,
                                           List<com.innbucks.marketplaceservice.checkout.dto.SellerDeliveryChoice> choices) {
        return new CheckoutQuoteRequest(null, List.of(new CheckoutQuoteRequest.Item(LISTING, 1),
                new CheckoutQuoteRequest.Item(OTHER_LISTING, 1)), basketDefault, addressId, null,
                choices);
    }

    private static com.innbucks.marketplaceservice.checkout.dto.SellerDeliveryChoice choice(
            UUID merchantId, DeliveryMethod method) {
        return new com.innbucks.marketplaceservice.checkout.dto.SellerDeliveryChoice(merchantId, method);
    }

    /** SELLER delivers Harare for 200 and OTHER_SELLER for 500; both collect. */
    private void twoSellersBothWays() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10),
                sellableBy(OTHER_LISTING, OTHER_SELLER, "Wireless Earbuds", 2599)));
        when(coverage.findByListingIdIn(any())).thenReturn(List.of(
                new ListingDeliveryTown(LISTING, "harare", 200),
                new ListingDeliveryTown(OTHER_LISTING, "harare", 500)));
    }

    @Test
    @DisplayName("A per-seller choice on a cell that has not switched it on is 422, before anything is loaded")
    void perSellerChoicesRefusedWhileTheSwitchIsOff() {
        ApiException ex = catchApi(() -> service.quote(BUYER, perSeller(DeliveryMethod.COLLECTION,
                null, List.of(choice(SELLER, DeliveryMethod.DELIVERY)))));

        assertThat(ex.status()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.code()).isEqualTo("seller_delivery_methods_disabled");
        assertThat(ex.getMessage()).isEqualTo("Choosing delivery or collection per seller is not "
                + "available yet - choose one method for the whole order");
        verifyNoInteractions(listingRepository, coverage, addressService);
    }

    @Test
    @DisplayName("An absent, empty or all-null choice list is no choice at all - allowed with the switch off")
    void noRealChoiceIsAllowedWhileTheSwitchIsOff() {
        twoSellersBothWays();
        java.util.List<com.innbucks.marketplaceservice.checkout.dto.SellerDeliveryChoice> onlyNulls =
                new java.util.ArrayList<>();
        onlyNulls.add(null);

        for (var choices : java.util.Arrays.asList(null, List.<com.innbucks.marketplaceservice.checkout.dto.SellerDeliveryChoice>of(), onlyNulls)) {
            CheckoutQuoteResponse quote = service.quote(BUYER,
                    perSeller(DeliveryMethod.COLLECTION, null, choices));
            assertThat(quote.deliveryMethod()).isEqualTo(DeliveryMethod.COLLECTION);
            assertThat(quote.sellers()).extracting(CheckoutQuoteResponse.QuoteSeller::deliveryMethod)
                    .containsOnly(DeliveryMethod.COLLECTION);
        }
        assertThat(service.resolveSellerChoices(onlyNulls)).isEmpty();
    }

    @Test
    @DisplayName("One seller named twice is 400 - even with the same method both times")
    void aSellerNamedTwiceIsRefused() {
        properties.getDelivery().setPerSellerMethodsEnabled(true);

        ApiException ex = catchApi(() -> service.quote(BUYER, perSeller(DeliveryMethod.COLLECTION,
                null, List.of(choice(SELLER, DeliveryMethod.DELIVERY),
                        choice(SELLER, DeliveryMethod.DELIVERY)))));

        assertThat(ex.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("duplicate_delivery_method_choice");
        assertThat(ex.getMessage()).isEqualTo("sellerDeliveryMethods names the same seller more than once");
        verifyNoInteractions(listingRepository);
    }

    @Test
    @DisplayName("A method the cell does not offer is 422 in resolveMethod's words, naming the seller")
    void anUnofferedChoiceNamesTheSeller() {
        properties.getDelivery().setPerSellerMethodsEnabled(true);
        properties.getDelivery().setMethods(EnumSet.of(DeliveryMethod.COLLECTION));

        ApiException ex = catchApi(() -> service.quote(BUYER, perSeller(null, null,
                List.of(choice(OTHER_SELLER, DeliveryMethod.COLLECTION),
                        choice(SELLER, DeliveryMethod.DELIVERY)))));

        assertThat(ex.status()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.code()).isEqualTo("delivery_method_unavailable");
        assertThat(ex.getMessage()).isEqualTo("DELIVERY is not available in this market");
        assertThat(ex.details()).isEqualTo(java.util.Map.of("merchantId", SELLER.toString()));
        verifyNoInteractions(listingRepository);
    }

    @Test
    @DisplayName("A null entry is skipped and a choice for a seller not in the basket is ignored")
    void nullEntriesSkippedAndStaleSellersIgnored() {
        properties.getDelivery().setPerSellerMethodsEnabled(true);
        twoSellersBothWays();
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());
        java.util.List<com.innbucks.marketplaceservice.checkout.dto.SellerDeliveryChoice> choices =
                new java.util.ArrayList<>();
        choices.add(null);
        choices.add(choice(STALE_SELLER, DeliveryMethod.DELIVERY));
        choices.add(choice(OTHER_SELLER, DeliveryMethod.DELIVERY));

        CheckoutQuoteResponse quote = service.quote(BUYER,
                perSeller(DeliveryMethod.COLLECTION, null, choices));

        // The stale seller is nowhere; the named one delivers, the other keeps the default.
        assertThat(quote.sellers()).containsExactly(
                new CheckoutQuoteResponse.QuoteSeller(SELLER, DeliveryMethod.COLLECTION,
                        List.of(DeliveryMethod.DELIVERY, DeliveryMethod.COLLECTION), null),
                new CheckoutQuoteResponse.QuoteSeller(OTHER_SELLER, DeliveryMethod.DELIVERY,
                        List.of(DeliveryMethod.DELIVERY, DeliveryMethod.COLLECTION), 500L));
        assertThat(quote.checkoutReady()).isTrue();
        // A choice list naming ONLY a stale seller is the uniform plan.
        CheckoutQuoteResponse onlyStale = service.quote(BUYER, perSeller(DeliveryMethod.COLLECTION,
                null, List.of(choice(STALE_SELLER, DeliveryMethod.DELIVERY))));
        assertThat(onlyStale.deliveryMethod()).isEqualTo(DeliveryMethod.COLLECTION);
        assertThat(onlyStale.sellers()).extracting(CheckoutQuoteResponse.QuoteSeller::deliveryMethod)
                .containsOnly(DeliveryMethod.COLLECTION);
    }

    @Test
    @DisplayName("A mixed quote: the address is resolved, only the delivering seller is charged, only the collecting one is on collectionPoints")
    void aMixedQuoteChargesAndCollectsPerSeller() {
        properties.getDelivery().setPerSellerMethodsEnabled(true);
        twoSellersBothWays();
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());

        CheckoutQuoteResponse quote = service.quote(BUYER, perSeller(DeliveryMethod.COLLECTION,
                null, List.of(choice(OTHER_SELLER, DeliveryMethod.DELIVERY))));

        // The summary: DELIVERY, because someone delivers - and so an address.
        assertThat(quote.deliveryMethod()).isEqualTo(DeliveryMethod.DELIVERY);
        assertThat(quote.deliveryAddress()).isNotNull();
        assertThat(quote.availabilityTownCode()).isEqualTo("harare");
        verify(addressService).requireForCheckout(BUYER, null);
        verify(addressService, never()).findForQuote(any(), any());
        // One fee: the delivering seller's.
        assertThat(quote.deliveryFees()).containsExactly(
                new CheckoutQuoteResponse.SellerDeliveryFee(OTHER_SELLER, 500));
        assertThat(quote.deliveryFeeCents()).isEqualTo(500);
        assertThat(quote.subtotalCents()).isEqualTo(1550 + 2599);
        assertThat(quote.totalCents()).isEqualTo(1550 + 2599 + 500);
        // Where the collecting seller is collected - and only them.
        verify(collectionPoints).resolve(List.of(SELLER), null);
        assertThat(quote.collectionPoints()).extracting(
                com.innbucks.marketplaceservice.pickup.dto.SellerCollectionPoint::merchantId)
                .containsExactly(SELLER);
        assertThat(quote.checkoutReady()).isTrue();
    }

    @Test
    @DisplayName("The address is needed exactly when some seller delivers: a DELIVERY default whose every seller collects asks for none")
    void addressRequiredExactlyWhenSomeoneDelivers() {
        properties.getDelivery().setPerSellerMethodsEnabled(true);
        twoSellersBothWays();
        when(addressService.findForQuote(any(), any())).thenReturn(Optional.empty());

        CheckoutQuoteResponse quote = service.quote(BUYER, perSeller(DeliveryMethod.DELIVERY, null,
                List.of(choice(SELLER, DeliveryMethod.COLLECTION),
                        choice(OTHER_SELLER, DeliveryMethod.COLLECTION))));

        assertThat(quote.deliveryMethod()).isEqualTo(DeliveryMethod.COLLECTION);
        assertThat(quote.deliveryAddress()).isNull();
        assertThat(quote.deliveryFees()).isEmpty();
        verify(addressService, never()).requireForCheckout(any(), any());
        assertThat(quote.collectionPoints()).extracting(
                com.innbucks.marketplaceservice.pickup.dto.SellerCollectionPoint::merchantId)
                .containsExactly(SELLER, OTHER_SELLER);

        // ...and a COLLECTION default with ONE seller delivering needs one: with
        // no saved address that is the usual 400, never a quote with nowhere to send.
        when(addressService.requireForCheckout(any(), any()))
                .thenThrow(ApiException.badRequest("delivery_address_required",
                        "Choose a delivery address, or add one first"));
        assertThat(catchApi(() -> service.quote(BUYER, perSeller(DeliveryMethod.COLLECTION, null,
                List.of(choice(SELLER, DeliveryMethod.DELIVERY))))).code())
                .isEqualTo("delivery_address_required");
    }

    @Test
    @DisplayName("A delivery-only seller chosen for DELIVERY beside a collecting seller is ready; the same basket uniform is not")
    void aDeliveryOnlySellerBesideACollectingOneIsReadyWhenMixed() {
        properties.getDelivery().setPerSellerMethodsEnabled(true);
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(1550, 10),
                sellableBy(OTHER_LISTING, OTHER_SELLER, "Wireless Earbuds", 2599)));
        // SELLER does not deliver to Harare; OTHER_SELLER only delivers.
        when(coverage.findByListingIdIn(any())).thenReturn(List.of(
                new ListingDeliveryTown(LISTING, "bulawayo", 900),
                new ListingDeliveryTown(OTHER_LISTING, "harare", 500)));
        when(sellerRepository.findCollectionDisabledAmong(any())).thenReturn(Set.of(OTHER_SELLER));
        when(addressService.requireForCheckout(any(), any())).thenReturn(address());
        when(addressService.findForQuote(any(), any())).thenReturn(Optional.of(address()));

        assertThat(service.quote(BUYER, twoSellers(DeliveryMethod.DELIVERY, null)).checkoutReady())
                .isFalse();
        assertThat(service.quote(BUYER, twoSellers(DeliveryMethod.COLLECTION, null)).checkoutReady())
                .isFalse();

        CheckoutQuoteResponse mixed = service.quote(BUYER, perSeller(DeliveryMethod.COLLECTION, null,
                List.of(choice(OTHER_SELLER, DeliveryMethod.DELIVERY))));
        assertThat(mixed.checkoutReady()).isTrue();
        assertThat(mixed.rejections()).isNull();
        assertThat(mixed.sellers()).containsExactly(
                new CheckoutQuoteResponse.QuoteSeller(SELLER, DeliveryMethod.COLLECTION,
                        List.of(DeliveryMethod.COLLECTION), null),
                new CheckoutQuoteResponse.QuoteSeller(OTHER_SELLER, DeliveryMethod.DELIVERY,
                        List.of(DeliveryMethod.DELIVERY), 500L));
    }

    @Test
    @DisplayName("options says perSellerDeliveryMethods only when the switch is on AND the cell offers both methods")
    void optionsAdvertisePerSellerOnlyWhenItCanWork() {
        assertThat(service.options().perSellerDeliveryMethods()).isFalse();

        properties.getDelivery().setPerSellerMethodsEnabled(true);
        assertThat(service.options().perSellerDeliveryMethods()).isTrue();

        properties.getDelivery().setMethods(EnumSet.of(DeliveryMethod.DELIVERY));
        assertThat(service.options().perSellerDeliveryMethods()).isFalse();
    }

    private static ApiException catchApi(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }
}
