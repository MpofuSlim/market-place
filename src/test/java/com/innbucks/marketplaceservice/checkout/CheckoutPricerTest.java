package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.support.TestTowns;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTown;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the ONE definition of "can this be bought, and what does it cost right
 * now" — the resolver the cart read, the checkout quote and order creation all
 * share. If these three ever answer differently, a shopper sees an item in
 * stock on the cart, priced on the checkout, and refused on the order.
 */
class CheckoutPricerTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    private ListingRepository listingRepository;
    private com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository variantRepository;
    private ListingDeliveryTownRepository coverage;
    private CheckoutPricer pricer;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        coverage = mock(ListingDeliveryTownRepository.class);
        variantRepository = mock(com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository.class);
        pricer = new CheckoutPricer(listingRepository, coverage, TestTowns.zimbabwe(), "USD",
                variantRepository);
    }

    private static Listing listing(UUID id, long priceCents, int stock, ListingStatus status,
                                   String currency) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(id).merchantId(UUID.randomUUID()).title("Solar Lantern 20W")
                .priceCents(priceCents).currency(currency).stockQty(stock)
                .status(status).createdAt(now).updatedAt(now)
                .build();
    }

    private static Listing sellable(UUID id, long priceCents, int stock) {
        return listing(id, priceCents, stock, ListingStatus.ACTIVE, "USD");
    }

    @Test
    @DisplayName("An empty basket costs nothing and touches no repository")
    void emptyBasket() {
        PricedBasket priced = pricer.price(List.of());

        assertThat(priced.lines()).isEmpty();
        assertThat(priced.subtotalCents()).isZero();
        assertThat(priced.issues()).isEmpty();
        // Not checkout-ready: there is nothing to buy. An empty basket must not
        // be allowed to become a zero-total order.
        assertThat(priced.checkoutReady()).isFalse();
        verify(listingRepository, times(0)).findAllById(any());
    }

    @Test
    @DisplayName("Sellable lines price at the listing's CURRENT price and sum into the subtotal")
    void pricesFromLiveListings() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(A, 1550, 10), sellable(B, 450, 10)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 2), new BasketLine(B, 1)));

        assertThat(priced.subtotalCents()).isEqualTo(3550);
        assertThat(priced.checkoutReady()).isTrue();
        assertThat(priced.lines()).extracting(PricedLine::lineTotalCents)
                .containsExactly(3100L, 450L);
    }

    @Test
    @DisplayName("Lines keep REQUEST order so a client can render its own list unchanged")
    void preservesRequestOrder() {
        // Deliberately returned in the opposite order — a repository is free to.
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(B, 450, 10), sellable(A, 1550, 10)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)));

        assertThat(priced.lines()).extracting(PricedLine::listingId).containsExactly(A, B);
    }

    @Test
    @DisplayName("Missing, inactive and foreign-currency listings share ONE reason: drop the line")
    void unavailableCollapsesToOneReason() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listing(B, 450, 10, ListingStatus.INACTIVE, "USD")));

        // A is absent from the repository entirely; B is off sale.
        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)));

        assertThat(priced.issues()).extracting(OrderLineRejection::reason)
                .containsExactly(OrderLineRejection.REASON_UNAVAILABLE,
                        OrderLineRejection.REASON_UNAVAILABLE);
        assertThat(priced.checkoutReady()).isFalse();
    }

    @Test
    @DisplayName("An unavailable listing's option line still names the option, so the app can find the line")
    void unavailableOptionLineCarriesTheLabel() {
        Listing offSale = listing(A, 1999, 10, ListingStatus.INACTIVE, "USD");
        offSale.setHasVariants(true);
        offSale.setOption1Name("Size");
        offSale.setOption2Name("Colour");
        UUID variantId = UUID.randomUUID();
        var xl = com.innbucks.marketplaceservice.catalog.variant.ListingVariant.builder()
                .id(variantId).listingId(A).stockQty(6).position(0).build();
        xl.setValues("XL", "Black");
        when(listingRepository.findAllById(any())).thenReturn(List.of(offSale));
        when(variantRepository.findAllById(any())).thenReturn(List.of(xl));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1, variantId)));

        OrderLineRejection issue = priced.issues().getFirst();
        assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_UNAVAILABLE);
        // The message is the pre-V19 one, byte for byte; only the echo is new.
        assertThat(issue.message()).isEqualTo("Listing " + A + " is not available");
        assertThat(issue.variantId()).isEqualTo(variantId);
        assertThat(issue.variantLabel()).isEqualTo("XL - Black");
    }

    @Test
    @DisplayName("An option of ANOTHER listing is never echoed as this line's label")
    void unavailableLineNeverEchoesAForeignOption() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listing(A, 1999, 10, ListingStatus.INACTIVE, "USD")));
        UUID foreignId = UUID.randomUUID();
        var foreign = com.innbucks.marketplaceservice.catalog.variant.ListingVariant.builder()
                .id(foreignId).listingId(B).stockQty(3).position(0).build();
        foreign.setValues("Secret", null);
        when(variantRepository.findAllById(any())).thenReturn(List.of(foreign));

        OrderLineRejection issue = pricer.price(List.of(new BasketLine(A, 1, foreignId)))
                .issues().getFirst();

        assertThat(issue.variantId()).isEqualTo(foreignId);
        assertThat(issue.variantLabel()).isNull();
    }

    @Test
    @DisplayName("A foreign-currency listing is unavailable, not mispriced")
    void foreignCurrencyIsUnavailable() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listing(A, 1550, 10, ListingStatus.ACTIVE, "ZWG")));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1)));

        assertThat(priced.issues()).singleElement()
                .extracting(OrderLineRejection::reason)
                .isEqualTo(OrderLineRejection.REASON_UNAVAILABLE);
    }

    @Test
    @DisplayName("An unavailable line carries NO listing — nothing downstream may price or name it")
    void unavailableLineCarriesNoListing() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listing(A, 1550, 10, ListingStatus.ARCHIVED, "USD")));

        PricedLine line = pricer.price(List.of(new BasketLine(A, 1))).lines().getFirst();

        assertThat(line.listing()).isNull();
        assertThat(line.sellable()).isFalse();
        assertThat(line.unitPriceCents()).isZero();
    }

    @Test
    @DisplayName("A short line reports how many are left and its current price, so one round-trip fixes it")
    void shortStockReportsWhatIsLeft() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(A, 1550, 3)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 5)));

        assertThat(priced.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_INSUFFICIENT_STOCK);
            assertThat(issue.availableQty()).isEqualTo(3);
            assertThat(issue.requestedQty()).isEqualTo(5);
            assertThat(issue.unitPriceCents()).isEqualTo(1550);
            assertThat(issue.message()).isEqualTo("Only 3 left of Solar Lantern 20W");
        });
        // A short line still knows its listing — the cart must render it with a
        // "only 3 left" badge, not blank it out.
        assertThat(priced.lines().getFirst().listing()).isNotNull();
    }

    @Test
    @DisplayName("An unbuyable line contributes ZERO — a subtotal is never a figure nobody can be charged")
    void unsellableLinesNeverCountTowardTheSubtotal() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(A, 1550, 0), sellable(B, 450, 10)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 2)));

        assertThat(priced.lines().getFirst().lineTotalCents()).isZero();
        assertThat(priced.subtotalCents()).isEqualTo(900);
    }

    @Test
    @DisplayName("Every failing line is reported at once, not just the first")
    void reportsEveryFailureTogether() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(B, 450, 1)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 9)));

        assertThat(priced.issues()).hasSize(2);
    }

    @Test
    @DisplayName("A subtotal that cannot be represented is refused, never wrapped around")
    void overflowIsRefused() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(A, Long.MAX_VALUE / 2, 100),
                        sellable(B, Long.MAX_VALUE / 2, 100)));

        assertThatThrownBy(() -> pricer.price(List.of(new BasketLine(A, 2), new BasketLine(B, 2))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("order_total_overflow");
    }

    @Test
    @DisplayName("Every listing is loaded in ONE query regardless of basket size")
    void loadsListingsInOneQuery() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(A, 100, 10), sellable(B, 100, 10)));

        pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)));

        verify(listingRepository, times(1)).findAllById(any());
    }

    // ------------------------------------------------------------------
    // Delivery coverage + per-seller fees (V14)
    // ------------------------------------------------------------------

    private static Listing sellableBy(UUID id, UUID merchantId, long priceCents) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(id).merchantId(merchantId).title("Solar Lantern 20W")
                .priceCents(priceCents).currency("USD").stockQty(10)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now)
                .build();
    }

    @Test
    @DisplayName("A DELIVERY basket pays the seller's fee to the buyer's town")
    void deliveryAddsTheTownFee() {
        UUID seller = UUID.randomUUID();
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellableBy(A, seller, 1550)));
        when(coverage.findByListingIdIn(any())).thenReturn(List.of(
                new ListingDeliveryTown(A, "harare", 300),
                new ListingDeliveryTown(A, "bulawayo", 1200)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 2)),
                DeliveryMethod.DELIVERY, "bulawayo");

        assertThat(priced.checkoutReady()).isTrue();
        assertThat(priced.subtotalCents()).isEqualTo(3100);
        assertThat(priced.deliveryFeeCents()).isEqualTo(1200);
        assertThat(priced.deliveryFeesByMerchant()).containsExactly(Map.entry(seller, 1200L));
    }

    @Test
    @DisplayName("One seller's lines are ONE parcel: the fee is the dearest line's, never the sum")
    void oneSellerPaysOneFee() {
        UUID seller = UUID.randomUUID();
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellableBy(A, seller, 1550), sellableBy(B, seller, 450)));
        when(coverage.findByListingIdIn(any())).thenReturn(List.of(
                new ListingDeliveryTown(A, "harare", 300),
                new ListingDeliveryTown(B, "harare", 500)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)),
                DeliveryMethod.DELIVERY, "harare");

        assertThat(priced.deliveryFeeCents()).isEqualTo(500);
        assertThat(priced.deliveryFeesByMerchant()).containsExactly(Map.entry(seller, 500L));
    }

    @Test
    @DisplayName("Two sellers ship two parcels, and each is paid their own fee")
    void eachSellerPaysTheirOwnFee() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellableBy(A, first, 1550), sellableBy(B, second, 450)));
        when(coverage.findByListingIdIn(any())).thenReturn(List.of(
                new ListingDeliveryTown(A, "harare", 300),
                new ListingDeliveryTown(B, "harare", 0)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)),
                DeliveryMethod.DELIVERY, "harare");

        // A free-delivery seller is still a parcel with a (zero) fee row.
        assertThat(priced.deliveryFeeCents()).isEqualTo(300);
        assertThat(priced.deliveryFeesByMerchant())
                .containsOnly(Map.entry(first, 300L), Map.entry(second, 0L));
    }

    @Test
    @DisplayName("A line that does not deliver to the town is an issue naming the town, and costs nothing")
    void uncoveredLineIsAnIssue() {
        UUID seller = UUID.randomUUID();
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellableBy(A, seller, 1550), sellableBy(B, seller, 450)));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(A, "harare", 300)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)),
                DeliveryMethod.DELIVERY, "victoria-falls");

        assertThat(priced.checkoutReady()).isFalse();
        assertThat(priced.issues()).hasSize(2).allSatisfy(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_NOT_DELIVERED_TO_TOWN);
            assertThat(issue.message()).endsWith("is not delivered to Victoria Falls");
        });
        assertThat(priced.subtotalCents()).isZero();
        assertThat(priced.deliveryFeeCents()).isZero();
    }

    @Test
    @DisplayName("COLLECTION prices goods only and never reads coverage")
    void collectionIgnoresCoverage() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(A, 1550, 10)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 1)),
                DeliveryMethod.COLLECTION, null);

        assertThat(priced.checkoutReady()).isTrue();
        assertThat(priced.deliveryFeeCents()).isZero();
        verify(coverage, times(0)).findByListingIdIn(any());
    }

    @Test
    @DisplayName("Coverage for the whole basket is ONE query")
    void loadsCoverageInOneQuery() {
        UUID seller = UUID.randomUUID();
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellableBy(A, seller, 100), sellableBy(B, seller, 100)));

        pricer.price(List.of(new BasketLine(A, 1), new BasketLine(B, 1)),
                DeliveryMethod.DELIVERY, "harare");

        verify(coverage, times(1)).findByListingIdIn(any());
    }

    // ------------------------------------------------------------------
    // Product options (V19)
    // ------------------------------------------------------------------

    // The canonical Swagger data: "Cotton Crew Tee" at 1999, Size x Colour,
    // M/Black 4 left, L/Black sold out, XL/Black 6 left at its own 2299.
    private static final UUID TEE = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID M_BLACK = UUID.fromString("0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352");
    private static final UUID L_BLACK = UUID.fromString("1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");
    private static final UUID SELLER = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");

    /** The tee: a listing WITH options, whose stockQty is the options' total (4 + 0 + 6). */
    private static Listing tee() {
        return withOptions(TEE, "Cotton Crew Tee", 1999, 10, "Size", "Colour");
    }

    private static Listing withOptions(UUID id, String title, long priceCents, int stockTotal,
                                       String option1, String option2) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(id).merchantId(SELLER).title(title)
                .priceCents(priceCents).currency("USD").stockQty(stockTotal)
                .hasVariants(true).option1Name(option1).option2Name(option2)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now)
                .build();
    }

    private static ListingVariant option(UUID id, UUID listingId, String size, String colour,
                                         Long priceOverrideCents, int stock) {
        Instant now = Instant.now();
        ListingVariant variant = ListingVariant.builder()
                .id(id).listingId(listingId).priceCents(priceOverrideCents).stockQty(stock)
                .createdAt(now).updatedAt(now).version(0L)
                .build();
        variant.setValues(size, colour);
        return variant;
    }

    private static ListingVariant mBlack() {
        return option(M_BLACK, TEE, "M", "Black", null, 4);
    }

    private static ListingVariant lBlack() {
        return option(L_BLACK, TEE, "L", "Black", null, 0);
    }

    private static ListingVariant xlBlack() {
        return option(XL_BLACK, TEE, "XL", "Black", 2299L, 6);
    }

    @Test
    @DisplayName("An option's own price reaches BOTH its line and the subtotal; an option without one sells at the listing price")
    void optionPriceReachesLineAndSubtotal() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee(), sellable(A, 450, 10)));
        when(variantRepository.findAllById(any())).thenReturn(List.of(xlBlack(), mBlack()));

        PricedBasket priced = pricer.price(List.of(
                new BasketLine(TEE, 2, XL_BLACK),
                new BasketLine(TEE, 1, M_BLACK),
                new BasketLine(A, 1)));

        assertThat(priced.checkoutReady()).isTrue();
        assertThat(priced.lines()).extracting(PricedLine::unitPriceCents)
                .containsExactly(2299L, 1999L, 450L);
        assertThat(priced.lines()).extracting(PricedLine::lineTotalCents)
                .containsExactly(4598L, 1999L, 450L);
        // The subtotal is the sum of the lines' OWN totals — never a second,
        // independent listing.priceCents x quantity that would price XL at 1999.
        assertThat(priced.subtotalCents()).isEqualTo(4598 + 1999 + 450);
        assertThat(priced.lines()).extracting(PricedLine::variantLabel)
                .containsExactly("XL - Black", "M - Black", null);
        assertThat(priced.lines().getFirst().variant().getId()).isEqualTo(XL_BLACK);
    }

    @Test
    @DisplayName("INSUFFICIENT_STOCK reads the OPTION's stock and names it: \"Cotton Crew Tee (L - Black) is sold out\"")
    void shortStockIsTheOptionsAndNamesIt() {
        // The listing's total is 10, which would cover every line below — the
        // option's own count is the only one that decides.
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of(lBlack(), mBlack()));

        PricedBasket priced = pricer.price(List.of(
                new BasketLine(TEE, 1, L_BLACK),
                new BasketLine(TEE, 5, M_BLACK)));

        assertThat(priced.checkoutReady()).isFalse();
        assertThat(priced.issues()).hasSize(2);
        assertThat(priced.issues().get(0)).satisfies(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_INSUFFICIENT_STOCK);
            assertThat(issue.message()).isEqualTo("Cotton Crew Tee (L - Black) is sold out");
            assertThat(issue.requestedQty()).isEqualTo(1);
            assertThat(issue.availableQty()).isZero();
            assertThat(issue.unitPriceCents()).isEqualTo(1999);
            assertThat(issue.variantId()).isEqualTo(L_BLACK);
            assertThat(issue.variantLabel()).isEqualTo("L - Black");
        });
        assertThat(priced.issues().get(1)).satisfies(issue -> {
            assertThat(issue.message()).isEqualTo("Only 4 left of Cotton Crew Tee (M - Black)");
            assertThat(issue.availableQty()).isEqualTo(4);
            assertThat(issue.variantId()).isEqualTo(M_BLACK);
            assertThat(issue.variantLabel()).isEqualTo("M - Black");
        });
        // Still rendered with its listing and option, like any short line.
        assertThat(priced.lines()).allSatisfy(line -> {
            assertThat(line.listing()).isNotNull();
            assertThat(line.variant()).isNotNull();
            assertThat(line.lineTotalCents()).isZero();
        });
        assertThat(priced.subtotalCents()).isZero();
    }

    @Test
    @DisplayName("A short option's issue carries the OPTION's price, not the listing's from-price")
    void shortOptionCarriesItsOwnPrice() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of(xlBlack()));

        OrderLineRejection issue = pricer.price(List.of(new BasketLine(TEE, 7, XL_BLACK)))
                .issues().getFirst();

        assertThat(issue.message()).isEqualTo("Only 6 left of Cotton Crew Tee (XL - Black)");
        assertThat(issue.unitPriceCents()).isEqualTo(2299);
    }

    @Test
    @DisplayName("An option line never consults the listing's total: the option's own stock is the only count")
    void optionLineIgnoresTheListingTotal() {
        // A total that has drifted to 0 must not refuse an option with stock —
        // the guarded option UPDATE is the authoritative check, and the pricer
        // must agree with it.
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(withOptions(TEE, "Cotton Crew Tee", 1999, 0, "Size", "Colour")));
        when(variantRepository.findAllById(any())).thenReturn(List.of(xlBlack()));

        PricedBasket priced = pricer.price(List.of(new BasketLine(TEE, 6, XL_BLACK)));

        assertThat(priced.checkoutReady()).isTrue();
        assertThat(priced.subtotalCents()).isEqualTo(6 * 2299);
    }

    @Test
    @DisplayName("A listing with options and no option chosen is VARIANT_REQUIRED, naming what to choose")
    void noOptionChosenIsVariantRequired() {
        UUID oneAxis = new UUID(0, 9);
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                tee(), withOptions(oneAxis, "Leather Belt", 1200, 5, "Size", null)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(TEE, 1), new BasketLine(oneAxis, 2)));

        assertThat(priced.checkoutReady()).isFalse();
        assertThat(priced.issues()).extracting(OrderLineRejection::reason)
                .containsOnly(OrderLineRejection.REASON_VARIANT_REQUIRED);
        assertThat(priced.issues().get(0)).satisfies(issue -> {
            assertThat(issue.message()).isEqualTo("Choose a Size and Colour for Cotton Crew Tee");
            assertThat(issue.listingId()).isEqualTo(TEE);
            assertThat(issue.requestedQty()).isEqualTo(1);
            assertThat(issue.unitPriceCents()).isEqualTo(1999);
            assertThat(issue.availableQty()).isNull();
            assertThat(issue.variantId()).isNull();
            assertThat(issue.variantLabel()).isNull();
        });
        assertThat(priced.issues().get(1).message()).isEqualTo("Choose a Size for Leather Belt");
        assertThat(priced.subtotalCents()).isZero();
        // No line names an option, so there is nothing to look up.
        verify(variantRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("An option that no longer exists is VARIANT_UNAVAILABLE, echoing the id so the app can find the line")
    void missingOptionIsVariantUnavailable() {
        UUID removed = UUID.fromString("9f1d3c2b-8a7e-4d6f-b5c4-3a2e1f0d9c8b");
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of());

        PricedBasket priced = pricer.price(List.of(new BasketLine(TEE, 1, removed)));

        assertThat(priced.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_VARIANT_UNAVAILABLE);
            assertThat(issue.message()).isEqualTo(
                    "The option you chose for Cotton Crew Tee is no longer available - choose another");
            assertThat(issue.requestedQty()).isEqualTo(1);
            assertThat(issue.unitPriceCents()).isEqualTo(1999);
            assertThat(issue.variantId()).isEqualTo(removed);
            assertThat(issue.variantLabel()).isNull();
        });
        PricedLine line = priced.lines().getFirst();
        assertThat(line.variantId()).isEqualTo(removed);
        assertThat(line.variant()).isNull();
        assertThat(line.lineTotalCents()).isZero();
    }

    @Test
    @DisplayName("Another listing's option is VARIANT_UNAVAILABLE on this one, and its price never leaks onto the line")
    void foreignOptionIsVariantUnavailable() {
        UUID jacket = new UUID(0, 7);
        UUID jacketSizeM = new UUID(0, 8);
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        // A real, in-stock option — of the jacket, priced well above the tee.
        when(variantRepository.findAllById(any()))
                .thenReturn(List.of(option(jacketSizeM, jacket, "M", null, 5000L, 3)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(TEE, 1, jacketSizeM)));

        assertThat(priced.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_VARIANT_UNAVAILABLE);
            assertThat(issue.message()).startsWith("The option you chose for Cotton Crew Tee");
            assertThat(issue.variantId()).isEqualTo(jacketSizeM);
            // The tee's price, not the jacket option's 5000, and no label from
            // an option that is not this listing's.
            assertThat(issue.unitPriceCents()).isEqualTo(1999);
            assertThat(issue.variantLabel()).isNull();
        });
        assertThat(priced.lines().getFirst().variant()).isNull();
        assertThat(priced.lines().getFirst().unitPriceCents()).isEqualTo(1999);
    }

    @Test
    @DisplayName("An option id on a listing WITHOUT options is VARIANT_UNAVAILABLE, never silently sold as the plain item")
    void optionOnPlainListingIsVariantUnavailable() {
        // Neither a real option of ANOTHER listing (the tee's M) nor an option
        // row that names this very listing — a stale row the listing's
        // has_variants flag no longer vouches for — can match: the flag, not
        // the option table, decides whether a listing sells options.
        UUID stale = new UUID(0, 77);
        when(listingRepository.findAllById(any())).thenReturn(List.of(sellable(A, 1550, 10)));
        when(variantRepository.findAllById(any()))
                .thenReturn(List.of(mBlack(), option(stale, A, "M", null, null, 5)));

        PricedBasket priced = pricer.price(List.of(
                new BasketLine(A, 1, M_BLACK), new BasketLine(A, 1, stale)));

        assertThat(priced.issues()).hasSize(2).allSatisfy(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_VARIANT_UNAVAILABLE);
            assertThat(issue.message()).isEqualTo(
                    "The option you chose for Solar Lantern 20W is no longer available - choose another");
            assertThat(issue.unitPriceCents()).isEqualTo(1550);
            assertThat(issue.variantLabel()).isNull();
        });
        assertThat(priced.issues()).extracting(OrderLineRejection::variantId)
                .containsExactly(M_BLACK, stale);
        assertThat(priced.lines()).allSatisfy(line -> assertThat(line.variant()).isNull());
        assertThat(priced.subtotalCents()).isZero();
    }

    @Test
    @DisplayName("A VARIANT_* line KEEPS its listing, so the seller is still known (V18's collection points need them)")
    void variantIssuesKeepTheirListing() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of());

        PricedBasket priced = pricer.price(List.of(
                new BasketLine(TEE, 1),
                new BasketLine(TEE, 1, new UUID(0, 42))));

        assertThat(priced.issues()).extracting(OrderLineRejection::reason).containsExactly(
                OrderLineRejection.REASON_VARIANT_REQUIRED, OrderLineRejection.REASON_VARIANT_UNAVAILABLE);
        // Unlike LISTING_UNAVAILABLE, which hands on null: CheckoutService's
        // sellersOf drops a line whose listing is null.
        assertThat(priced.lines()).allSatisfy(line -> {
            assertThat(line.listing()).isNotNull();
            assertThat(line.listing().getMerchantId()).isEqualTo(SELLER);
            assertThat(line.sellable()).isFalse();
            assertThat(line.unitPriceCents()).isEqualTo(1999);
            assertThat(line.lineTotalCents()).isZero();
        });
    }

    @Test
    @DisplayName("An unavailable listing on an option line keeps its unchanged message but echoes the option id")
    void unavailableOptionLineEchoesTheOption() {
        Listing offSale = tee();
        offSale.setStatus(ListingStatus.INACTIVE);
        when(listingRepository.findAllById(any())).thenReturn(List.of(offSale));
        when(variantRepository.findAllById(any())).thenReturn(List.of(mBlack()));

        PricedBasket priced = pricer.price(List.of(new BasketLine(TEE, 1, M_BLACK)));

        assertThat(priced.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_UNAVAILABLE);
            assertThat(issue.message()).isEqualTo("Listing " + TEE + " is not available");
            assertThat(issue.variantId()).isEqualTo(M_BLACK);
            assertThat(issue.unitPriceCents()).isNull();
        });
        // Nothing downstream may price or name an unbuyable listing — nor its option.
        PricedLine line = priced.lines().getFirst();
        assertThat(line.listing()).isNull();
        assertThat(line.variant()).isNull();
        assertThat(line.variantId()).isEqualTo(M_BLACK);
    }

    @Test
    @DisplayName("Delivery coverage stays per LISTING: two sizes of one listing to a covered town are one seller's one fee")
    void coverageIsPerListing() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of(mBlack(), xlBlack()));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(TEE, "harare", 300)));

        PricedBasket priced = pricer.price(List.of(
                        new BasketLine(TEE, 1, M_BLACK), new BasketLine(TEE, 1, XL_BLACK)),
                DeliveryMethod.DELIVERY, "harare");

        assertThat(priced.checkoutReady()).isTrue();
        assertThat(priced.subtotalCents()).isEqualTo(1999 + 2299);
        assertThat(priced.deliveryFeeCents()).isEqualTo(300);
        assertThat(priced.deliveryFeesByMerchant()).containsExactly(Map.entry(SELLER, 300L));
        // Coverage is asked about the listing — once — never about its options.
        verify(coverage, times(1)).findByListingIdIn(List.of(TEE));
    }

    @Test
    @DisplayName("An option line not delivered to the town keeps the unchanged message and carries variantId + label")
    void uncoveredOptionLineCarriesTheOption() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee()));
        when(variantRepository.findAllById(any())).thenReturn(List.of(xlBlack()));
        when(coverage.findByListingIdIn(any()))
                .thenReturn(List.of(new ListingDeliveryTown(TEE, "harare", 300)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(TEE, 1, XL_BLACK)),
                DeliveryMethod.DELIVERY, "bulawayo");

        assertThat(priced.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.reason()).isEqualTo(OrderLineRejection.REASON_NOT_DELIVERED_TO_TOWN);
            assertThat(issue.message()).isEqualTo("Cotton Crew Tee is not delivered to Bulawayo");
            assertThat(issue.variantId()).isEqualTo(XL_BLACK);
            assertThat(issue.variantLabel()).isEqualTo("XL - Black");
            assertThat(issue.unitPriceCents()).isEqualTo(2299);
        });
        assertThat(priced.deliveryFeeCents()).isZero();
        assertThat(priced.subtotalCents()).isZero();
    }

    @Test
    @DisplayName("Every option a basket names is loaded in ONE query, alongside the ONE listing query")
    void loadsOptionsInOneQuery() {
        when(listingRepository.findAllById(any())).thenReturn(List.of(tee(), sellable(A, 450, 10)));
        when(variantRepository.findAllById(any())).thenReturn(List.of(mBlack(), xlBlack()));

        pricer.price(List.of(
                new BasketLine(TEE, 1, M_BLACK),
                new BasketLine(A, 1),
                new BasketLine(TEE, 1, XL_BLACK),
                new BasketLine(TEE, 1, M_BLACK)));

        verify(listingRepository, times(1)).findAllById(any());
        // One query, asking for each option once however often it is named.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> asked = ArgumentCaptor.forClass(Iterable.class);
        verify(variantRepository, times(1)).findAllById(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder(M_BLACK, XL_BLACK);
    }

    @Test
    @DisplayName("A basket that names no option never queries options at all")
    void plainBasketNeverQueriesOptions() {
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(sellable(A, 1550, 10), sellable(B, 450, 10)));

        PricedBasket priced = pricer.price(List.of(new BasketLine(A, 2), new BasketLine(B, 1)));

        assertThat(priced.subtotalCents()).isEqualTo(3550);
        verify(listingRepository, times(1)).findAllById(any());
        verify(variantRepository, never()).findAllById(any());
    }
}
