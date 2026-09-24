package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.support.TestTowns;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTown;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
