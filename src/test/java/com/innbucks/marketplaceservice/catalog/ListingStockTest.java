package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ListingStock} — THE one place stock moves (V19) — at the level
 * of the statements it issues, because the statement ORDER is the lock order
 * and the lock order is what keeps two orders from deadlocking.
 *
 * <p>The rules pinned here: listings are taken in {@code java.util.UUID}
 * order and options within a listing likewise, whatever order the lines
 * arrive in; a listing without variants issues exactly the pre-V19
 * {@code reserveStock(id, q)} per line; a listing with variants is locked
 * BEFORE any option moves and its total is recomputed AFTER; a failed reserve
 * gives back everything it took and announces nothing; a return never throws
 * for a data condition but meters every unit it could not credit; and
 * {@link ListingRestocked} fires only when a listing genuinely comes back
 * from zero, once per listing.
 */
class ListingStockTest {

    // Scrambled on purpose: the natural-order sort in listingIdsInLockOrder()
    // is the only thing that decides which is "first". One id has the high
    // bit set, where Java's signed compare and a string/unsigned compare
    // disagree — the lock order is Java's.
    private static final UUID L1 = UUID.fromString("5a0c7c1e-0000-4000-8000-000000000001");
    private static final UUID L2 = UUID.fromString("e3b1f2a4-0000-4000-8000-000000000002");
    private static final UUID L3 = UUID.fromString("0f00d00d-0000-4000-8000-000000000003");

    private ListingRepository listings;
    private ListingVariantRepository variants;
    private ApplicationEventPublisher eventPublisher;
    private SimpleMeterRegistry registry;
    private ListingStock stock;

    @BeforeEach
    void setUp() {
        listings = mock(ListingRepository.class);
        variants = mock(ListingVariantRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new SimpleMeterRegistry();
        stock = new ListingStock(listings, variants, eventPublisher, new MarketplaceMetrics(registry));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** The three listing ids in {@code UUID.compareTo} order. */
    private static List<UUID> listingIdsInLockOrder() {
        List<UUID> ids = new ArrayList<>(List.of(L1, L2, L3));
        ids.sort(UUID::compareTo);
        return ids;
    }

    /** Two option ids in {@code UUID.compareTo} order (index 0 first). */
    private static List<UUID> variantIdsInLockOrder() {
        List<UUID> ids = new ArrayList<>(List.of(
                UUID.fromString("c0ffee00-0000-4000-8000-00000000000b"),
                UUID.fromString("0bad1dea-0000-4000-8000-00000000000a")));
        ids.sort(UUID::compareTo);
        return ids;
    }

    private static StockLine plain(UUID listingId, int qty) {
        return new StockLine(listingId, null, qty);
    }

    private static StockLine option(UUID listingId, UUID variantId, int qty) {
        return new StockLine(listingId, variantId, qty);
    }

    private static StockRow row(String status, Boolean hasVariants, Integer stockQty) {
        return new StockRow() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public Boolean getHasVariants() {
                return hasVariants;
            }

            @Override
            public Integer getStockQty() {
                return stockQty;
            }
        };
    }

    private static StockRow activeVariantListing(int total) {
        return row("ACTIVE", true, total);
    }

    private static StockRow activePlainListing(int stockQty) {
        return row("ACTIVE", false, stockQty);
    }

    private double dropped(String reason) {
        Counter counter = registry.find("marketplace.stock.returns_dropped").tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double droppedTotal() {
        return registry.find("marketplace.stock.returns_dropped").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    // ------------------------------------------------------------------
    // Reserve
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("reserveAll")
    class Reserve {

        @Test
        @DisplayName("Plain listings issue exactly reserveStock(id, q) per line, in UUID order whatever order the lines arrive in")
        void plainLinesReserveInUuidOrder() {
            List<UUID> ids = listingIdsInLockOrder();
            when(listings.reserveStock(any(), anyInt())).thenReturn(1);

            StockLine refused = stock.reserveAll(List.of(
                    plain(ids.get(2), 3), plain(ids.get(0), 1), plain(ids.get(1), 2)));

            assertThat(refused).isNull();
            InOrder order = inOrder(listings);
            order.verify(listings).reserveStock(ids.get(0), 1);
            order.verify(listings).reserveStock(ids.get(1), 2);
            order.verify(listings).reserveStock(ids.get(2), 3);
            // Exactly the pre-V19 statements: no lock read, no recompute, no option touched.
            verifyNoMoreInteractions(listings);
            verifyNoInteractions(variants, eventPublisher);
        }

        @Test
        @DisplayName("A plain shortfall hands back exactly what was taken, via restock(id, q), and refuses the short line")
        void plainShortfallRestocksWhatWasTaken() {
            List<UUID> ids = listingIdsInLockOrder();
            when(listings.reserveStock(ids.get(0), 2)).thenReturn(1);
            when(listings.reserveStock(ids.get(1), 1)).thenReturn(1);
            when(listings.reserveStock(ids.get(2), 5)).thenReturn(0);
            StockLine shortLine = plain(ids.get(2), 5);

            StockLine refused = stock.reserveAll(List.of(shortLine, plain(ids.get(1), 1), plain(ids.get(0), 2)));

            assertThat(refused).isEqualTo(shortLine);
            InOrder order = inOrder(listings);
            order.verify(listings).reserveStock(ids.get(0), 2);
            order.verify(listings).reserveStock(ids.get(1), 1);
            order.verify(listings).reserveStock(ids.get(2), 5);
            verify(listings).restock(ids.get(0), 2);
            verify(listings).restock(ids.get(1), 1);
            // The short line itself was never taken, so it is never "given back".
            verify(listings, never()).restock(eq(ids.get(2)), anyInt());
            verify(listings, never()).recomputeStockTotal(any());
            verifyNoInteractions(variants, eventPublisher);
        }

        @Test
        @DisplayName("A variant listing is locked BEFORE any option is reserved, options go in UUID order, and the total is recomputed AFTER")
        void variantGroupLocksThenReservesThenRecomputes() {
            UUID listing = L1;
            List<UUID> opts = variantIdsInLockOrder();
            when(listings.lockForStock(listing)).thenReturn(activeVariantListing(9));
            when(variants.reserve(any(), eq(listing), anyInt())).thenReturn(1);
            when(listings.recomputeStockTotal(listing)).thenReturn(1);

            StockLine refused = stock.reserveAll(List.of(
                    option(listing, opts.get(1), 2), option(listing, opts.get(0), 1)));

            assertThat(refused).isNull();
            InOrder order = inOrder(listings, variants);
            order.verify(listings).lockForStock(listing);
            order.verify(variants).reserve(opts.get(0), listing, 1);
            order.verify(variants).reserve(opts.get(1), listing, 2);
            order.verify(listings).recomputeStockTotal(listing);
            // The plain guarded UPDATE is never used on a variant listing.
            verify(listings, never()).reserveStock(any(), anyInt());
            verifyNoMoreInteractions(listings, variants);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("A basket mixing plain and variant listings takes each listing whole, in UUID order")
        void mixedBasketFollowsListingOrder() {
            List<UUID> ids = listingIdsInLockOrder();
            UUID first = ids.get(0);
            UUID middle = ids.get(1);
            UUID last = ids.get(2);
            UUID opt = variantIdsInLockOrder().get(0);
            when(listings.reserveStock(any(), anyInt())).thenReturn(1);
            when(listings.lockForStock(middle)).thenReturn(activeVariantListing(4));
            when(variants.reserve(opt, middle, 1)).thenReturn(1);
            when(listings.recomputeStockTotal(middle)).thenReturn(1);

            StockLine refused = stock.reserveAll(List.of(
                    plain(last, 1), option(middle, opt, 1), plain(first, 2)));

            assertThat(refused).isNull();
            InOrder order = inOrder(listings, variants);
            order.verify(listings).reserveStock(first, 2);
            order.verify(listings).lockForStock(middle);
            order.verify(variants).reserve(opt, middle, 1);
            order.verify(listings).recomputeStockTotal(middle);
            order.verify(listings).reserveStock(last, 1);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("An option shortfall un-reserves earlier plain AND option lines, recomputes every variant listing it touched, and publishes nothing")
        void variantShortfallUndoesEverythingTaken() {
            List<UUID> ids = listingIdsInLockOrder();
            UUID plainListing = ids.get(0);
            UUID sized = ids.get(1);
            UUID coloured = ids.get(2);
            List<UUID> opts = variantIdsInLockOrder();
            UUID sizeS = opts.get(0);
            UUID sizeM = opts.get(1);
            UUID red = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
            UUID blue = UUID.fromString("7fffffff-0000-4000-8000-0000000000b2");

            when(listings.reserveStock(plainListing, 2)).thenReturn(1);
            when(listings.lockForStock(sized)).thenReturn(activeVariantListing(10));
            when(listings.lockForStock(coloured)).thenReturn(activeVariantListing(10));
            when(variants.reserve(any(), any(), anyInt())).thenReturn(1);
            when(variants.reserve(blue, coloured, 4)).thenReturn(0);
            when(listings.recomputeStockTotal(any())).thenReturn(1);
            StockLine shortLine = option(coloured, blue, 4);

            StockLine refused = stock.reserveAll(List.of(
                    shortLine, option(sized, sizeM, 1), plain(plainListing, 2),
                    option(coloured, red, 3), option(sized, sizeS, 2)));

            assertThat(refused).isEqualTo(shortLine);

            // The reserve itself, in lock order.
            InOrder reserve = inOrder(listings, variants);
            reserve.verify(listings).reserveStock(plainListing, 2);
            reserve.verify(listings).lockForStock(sized);
            reserve.verify(variants).reserve(sizeS, sized, 2);
            reserve.verify(variants).reserve(sizeM, sized, 1);
            reserve.verify(listings).recomputeStockTotal(sized);
            reserve.verify(listings).lockForStock(coloured);
            reserve.verify(variants).reserve(red, coloured, 3);
            reserve.verify(variants).reserve(blue, coloured, 4);

            // The compensation: every taken unit back where it was reserved...
            verify(listings).restock(plainListing, 2);
            verify(variants).restock(sizeS, sized, 2);
            verify(variants).restock(sizeM, sized, 1);
            verify(variants).restock(red, coloured, 3);
            // ...but never the line that was not taken.
            verify(variants, never()).restock(eq(blue), any(), anyInt());

            // Each touched variant listing's total is recomputed AFTER its options went back.
            InOrder sizedUndo = inOrder(variants, listings);
            sizedUndo.verify(variants).restock(sizeS, sized, 2);
            sizedUndo.verify(variants).restock(sizeM, sized, 1);
            sizedUndo.verify(listings).recomputeStockTotal(sized);
            InOrder colouredUndo = inOrder(variants, listings);
            colouredUndo.verify(variants).restock(red, coloured, 3);
            colouredUndo.verify(listings).recomputeStockTotal(coloured);
            // sized: once after its reserve, once after its undo; coloured: only the undo
            // (its own recompute never ran — the shortfall came first).
            verify(listings, times(2)).recomputeStockTotal(sized);
            verify(listings, times(1)).recomputeStockTotal(coloured);
            verify(listings, never()).recomputeStockTotal(plainListing);

            // Units that were never really out cannot be "back in stock".
            verifyNoInteractions(eventPublisher);
        }

        static Stream<Arguments> unsellableAtLockTime() {
            return Stream.of(
                    Arguments.of("the listing row is gone", null),
                    Arguments.of("the listing is INACTIVE", row("INACTIVE", true, 5)),
                    Arguments.of("the listing is still a DRAFT", row("DRAFT", true, 5)),
                    Arguments.of("the listing was converted to plain", row("ACTIVE", false, 5)),
                    Arguments.of("the variant flag reads null", row("ACTIVE", null, 5)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unsellableAtLockTime")
        @DisplayName("A variant group whose listing is not an ACTIVE variant listing at lock time is refused whole, before any option moves")
        void variantGroupRefusedAtLockTime(String why, StockRow atLock) {
            List<UUID> ids = listingIdsInLockOrder();
            UUID earlier = ids.get(0);
            UUID listing = ids.get(1);
            List<UUID> opts = variantIdsInLockOrder();
            when(listings.reserveStock(earlier, 1)).thenReturn(1);
            when(listings.lockForStock(listing)).thenReturn(atLock);
            StockLine firstOption = option(listing, opts.get(0), 1);

            StockLine refused = stock.reserveAll(List.of(
                    option(listing, opts.get(1), 2), plain(earlier, 1), firstOption));

            // The group's first line in lock order names the refusal.
            assertThat(refused).isEqualTo(firstOption);
            verify(variants, never()).reserve(any(), any(), anyInt());
            verify(listings, never()).recomputeStockTotal(any());
            // What an earlier listing had taken goes back.
            verify(listings).restock(earlier, 1);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("A plain line and an option line on ONE listing are refused without throwing, and nothing on that listing moves")
        void plainAndOptionOnOneListingRefused() {
            List<UUID> ids = listingIdsInLockOrder();
            UUID earlier = ids.get(0);
            UUID listing = ids.get(1);
            UUID opt = variantIdsInLockOrder().get(0);
            when(listings.reserveStock(earlier, 3)).thenReturn(1);
            StockLine plainLine = plain(listing, 1);

            // A refusal the caller turns into its 409 — never an exception (a 500).
            StockLine refused = stock.reserveAll(
                    List.of(option(listing, opt, 2), plain(earlier, 3), plainLine));

            // Plain sorts first within a listing, so the plain line names the refusal.
            assertThat(refused).isEqualTo(plainLine);
            verify(listings, never()).reserveStock(eq(listing), anyInt());
            verify(listings, never()).lockForStock(listing);
            verify(variants, never()).reserve(any(), any(), anyInt());
            verify(listings).restock(earlier, 3);
            verifyNoInteractions(eventPublisher);
        }
    }

    // ------------------------------------------------------------------
    // Return
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("returnAll, plain lines")
    class PlainReturn {

        @Test
        @DisplayName("A return that brings a plain listing back from 0 publishes ListingRestocked, reading 'before' under the lock first")
        void backFromZeroPublishes() {
            when(listings.lockForStock(L1)).thenReturn(activePlainListing(0));
            when(listings.restock(L1, 2)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(2);

            stock.returnAll(List.of(plain(L1, 2)));

            InOrder order = inOrder(listings, eventPublisher);
            order.verify(listings).lockForStock(L1);
            order.verify(listings).restock(L1, 2);
            order.verify(eventPublisher).publishEvent(new ListingRestocked(L1));
            verify(eventPublisher, times(1)).publishEvent(any(Object.class));
            assertThat(droppedTotal()).isZero();
        }

        @Test
        @DisplayName("A return onto a listing that still had stock is not a restock")
        void notFromZeroIsSilent() {
            when(listings.lockForStock(L1)).thenReturn(activePlainListing(3));
            when(listings.restock(L1, 2)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(5);

            stock.returnAll(List.of(plain(L1, 2)));

            verify(listings).restock(L1, 2);
            verifyNoInteractions(eventPublisher);
            assertThat(droppedTotal()).isZero();
        }

        @Test
        @DisplayName("A return whose listing row is gone meters listing_missing, publishes nothing and does not throw")
        void missingListingIsMetered() {
            when(listings.lockForStock(L1)).thenReturn(null);
            when(listings.restock(L1, 2)).thenReturn(0);

            assertThatCode(() -> stock.returnAll(List.of(plain(L1, 2)))).doesNotThrowAnyException();

            assertThat(dropped("listing_missing")).isEqualTo(1.0);
            assertThat(droppedTotal()).isEqualTo(1.0);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("A plain return onto a listing since converted to variants credits nothing, meters listing_converted, and does not publish even from 0")
        void convertedListingIsMeteredAndSilent() {
            // before = 0 on purpose: before V19 a plain return published regardless of
            // the count; now only a return that actually landed (count 1) can.
            when(listings.lockForStock(L1)).thenReturn(activeVariantListing(0));
            when(listings.restock(L1, 2)).thenReturn(0);
            when(listings.stockQtyOf(L1)).thenReturn(4);

            assertThatCode(() -> stock.returnAll(List.of(plain(L1, 2)))).doesNotThrowAnyException();

            assertThat(dropped("listing_converted")).isEqualTo(1.0);
            assertThat(droppedTotal()).isEqualTo(1.0);
            verify(variants, never()).restock(any(), any(), anyInt());
            verify(listings, never()).recomputeStockTotal(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Two lines of one plain listing back from 0 publish ONE ListingRestocked")
        void twoLinesOfOneListingPublishOnce() {
            when(listings.lockForStock(L1)).thenReturn(activePlainListing(0));
            when(listings.restock(eq(L1), anyInt())).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(3);

            stock.returnAll(List.of(plain(L1, 1), plain(L1, 2)));

            verify(listings).lockForStock(L1);
            verify(listings).restock(L1, 1);
            verify(listings).restock(L1, 2);
            verify(eventPublisher, times(1)).publishEvent(new ListingRestocked(L1));
        }

        @Test
        @DisplayName("A return with no status guard: a deactivated listing still gets its held units back")
        void inactiveListingStillCredited() {
            when(listings.lockForStock(L1)).thenReturn(row("INACTIVE", false, 0));
            when(listings.restock(L1, 2)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(2);

            stock.returnAll(List.of(plain(L1, 2)));

            verify(listings).restock(L1, 2);
            verify(eventPublisher).publishEvent(new ListingRestocked(L1));
            assertThat(droppedTotal()).isZero();
        }
    }

    @Nested
    @DisplayName("returnAll, option lines")
    class VariantReturn {

        @Test
        @DisplayName("Options go back under the listing lock, in UUID order, then the total is recomputed and read, and two lines publish ONCE")
        void lockThenRestockThenRecomputeThenPublishOnce() {
            List<UUID> opts = variantIdsInLockOrder();
            when(listings.lockForStock(L1)).thenReturn(activeVariantListing(0));
            when(variants.restock(any(), eq(L1), anyInt())).thenReturn(1);
            when(listings.recomputeStockTotal(L1)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(3);

            stock.returnAll(List.of(option(L1, opts.get(1), 2), option(L1, opts.get(0), 1)));

            InOrder order = inOrder(listings, variants, eventPublisher);
            order.verify(listings).lockForStock(L1);
            order.verify(variants).restock(opts.get(0), L1, 1);
            order.verify(variants).restock(opts.get(1), L1, 2);
            order.verify(listings).recomputeStockTotal(L1);
            order.verify(listings).stockQtyOf(L1);
            order.verify(eventPublisher).publishEvent(new ListingRestocked(L1));
            verify(eventPublisher, times(1)).publishEvent(any(Object.class));
            // An option return never uses the plain delta.
            verify(listings, never()).restock(any(), anyInt());
            assertThat(droppedTotal()).isZero();
        }

        @Test
        @DisplayName("A size coming back while another size still had stock is not a restock of the listing")
        void totalNotFromZeroIsSilent() {
            UUID opt = variantIdsInLockOrder().get(0);
            when(listings.lockForStock(L1)).thenReturn(activeVariantListing(4));
            when(variants.restock(opt, L1, 1)).thenReturn(1);
            when(listings.recomputeStockTotal(L1)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(5);

            stock.returnAll(List.of(option(L1, opt, 1)));

            verify(listings).recomputeStockTotal(L1);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Option lines whose listing row is gone are each metered listing_missing, and nothing is touched")
        void missingListing() {
            List<UUID> opts = variantIdsInLockOrder();
            when(listings.lockForStock(L1)).thenReturn(null);

            assertThatCode(() -> stock.returnAll(List.of(
                    option(L1, opts.get(0), 1), option(L1, opts.get(1), 2)))).doesNotThrowAnyException();

            assertThat(dropped("listing_missing")).isEqualTo(2.0);
            assertThat(droppedTotal()).isEqualTo(2.0);
            verifyNoInteractions(variants, eventPublisher);
            verify(listings, never()).recomputeStockTotal(any());
        }

        @Test
        @DisplayName("Option lines on a listing since converted to plain are each metered listing_converted: the seller's new count is the truth")
        void convertedToPlain() {
            List<UUID> opts = variantIdsInLockOrder();
            when(listings.lockForStock(L1)).thenReturn(activePlainListing(0));

            assertThatCode(() -> stock.returnAll(List.of(
                    option(L1, opts.get(0), 1), option(L1, opts.get(1), 2)))).doesNotThrowAnyException();

            assertThat(dropped("listing_converted")).isEqualTo(2.0);
            assertThat(droppedTotal()).isEqualTo(2.0);
            verifyNoInteractions(variants, eventPublisher);
            verify(listings, never()).restock(any(), anyInt());
            verify(listings, never()).setPlainStock(any(), anyInt());
            verify(listings, never()).recomputeStockTotal(any());
        }

        @Test
        @DisplayName("A removed option meters variant_removed while its siblings are still credited and the total recomputed")
        void removedOptionMeteredSiblingsCredited() {
            List<UUID> opts = variantIdsInLockOrder();
            UUID removed = opts.get(0);
            UUID kept = opts.get(1);
            when(listings.lockForStock(L1)).thenReturn(activeVariantListing(0));
            when(variants.restock(removed, L1, 1)).thenReturn(0);
            when(variants.restock(kept, L1, 2)).thenReturn(1);
            when(listings.recomputeStockTotal(L1)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(2);

            assertThatCode(() -> stock.returnAll(List.of(
                    option(L1, kept, 2), option(L1, removed, 1)))).doesNotThrowAnyException();

            assertThat(dropped("variant_removed")).isEqualTo(1.0);
            assertThat(droppedTotal()).isEqualTo(1.0);
            verify(listings).recomputeStockTotal(L1);
            verify(eventPublisher, times(1)).publishEvent(new ListingRestocked(L1));
        }

        @Test
        @DisplayName("When every option was removed nothing was credited: no recompute and no restock alert")
        void everyOptionRemoved() {
            UUID opt = variantIdsInLockOrder().get(0);
            when(listings.lockForStock(L1)).thenReturn(activeVariantListing(0));
            when(variants.restock(opt, L1, 3)).thenReturn(0);

            assertThatCode(() -> stock.returnAll(List.of(option(L1, opt, 3)))).doesNotThrowAnyException();

            assertThat(dropped("variant_removed")).isEqualTo(1.0);
            verify(listings, never()).recomputeStockTotal(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("A total that will not recompute meters invariant_broken, publishes nothing, does not throw, and the next listing is still returned")
        void brokenTotalNeverThrows() {
            List<UUID> ids = listingIdsInLockOrder();
            UUID broken = ids.get(0);
            UUID next = ids.get(1);
            UUID opt = variantIdsInLockOrder().get(0);
            when(listings.lockForStock(broken)).thenReturn(activeVariantListing(0));
            when(variants.restock(opt, broken, 1)).thenReturn(1);
            when(listings.recomputeStockTotal(broken)).thenReturn(0);
            when(listings.lockForStock(next)).thenReturn(activePlainListing(0));
            when(listings.restock(next, 2)).thenReturn(1);
            when(listings.stockQtyOf(next)).thenReturn(2);

            assertThatCode(() -> stock.returnAll(List.of(plain(next, 2), option(broken, opt, 1))))
                    .doesNotThrowAnyException();

            assertThat(dropped("invariant_broken")).isEqualTo(1.0);
            assertThat(droppedTotal()).isEqualTo(1.0);
            verify(listings, never()).stockQtyOf(broken);
            verify(eventPublisher, never()).publishEvent(new ListingRestocked(broken));
            // The broken listing did not stop the one after it.
            verify(listings).restock(next, 2);
            verify(eventPublisher).publishEvent(new ListingRestocked(next));
        }
    }

    @Nested
    @DisplayName("Return lock order")
    class ReturnOrder {

        @Test
        @DisplayName("Returns lock listings in the same UUID order reserves take them, whatever order the order's items come in")
        void returnsFollowTheReserveOrder() {
            List<UUID> ids = listingIdsInLockOrder();
            UUID opt = variantIdsInLockOrder().get(0);
            // The same scrambled lines, as the order's items would list them.
            List<StockLine> lines = List.of(plain(ids.get(2), 1), option(ids.get(1), opt, 2), plain(ids.get(0), 3));

            when(listings.reserveStock(any(), anyInt())).thenReturn(1);
            when(listings.lockForStock(ids.get(1))).thenReturn(activeVariantListing(5));
            when(variants.reserve(opt, ids.get(1), 2)).thenReturn(1);
            when(listings.recomputeStockTotal(ids.get(1))).thenReturn(1);
            assertThat(stock.reserveAll(lines)).isNull();

            InOrder reserve = inOrder(listings, variants);
            reserve.verify(listings).reserveStock(ids.get(0), 3);
            reserve.verify(listings).lockForStock(ids.get(1));
            reserve.verify(listings).reserveStock(ids.get(2), 1);

            clearInvocations(listings, variants);
            when(listings.lockForStock(any())).thenReturn(activePlainListing(7));
            when(listings.lockForStock(ids.get(1))).thenReturn(activeVariantListing(7));
            when(listings.restock(any(), anyInt())).thenReturn(1);
            when(variants.restock(any(), any(), anyInt())).thenReturn(1);

            stock.returnAll(lines);

            InOrder ret = inOrder(listings, variants);
            ret.verify(listings).lockForStock(ids.get(0));
            ret.verify(listings).restock(ids.get(0), 3);
            ret.verify(listings).lockForStock(ids.get(1));
            ret.verify(variants).restock(opt, ids.get(1), 2);
            ret.verify(listings).recomputeStockTotal(ids.get(1));
            ret.verify(listings).lockForStock(ids.get(2));
            ret.verify(listings).restock(ids.get(2), 1);
        }
    }

    // ------------------------------------------------------------------
    // Seller writes
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Seller writes")
    class SellerWrites {

        @Test
        @DisplayName("lock is the listing's locking read, and nothing else")
        void lockDelegatesToTheLockingRead() {
            StockRow held = activePlainListing(6);
            when(listings.lockForStock(L1)).thenReturn(held);

            assertThat(stock.lock(L1)).isSameAs(held);
            verify(listings).lockForStock(L1);
            verifyNoMoreInteractions(listings);
            verifyNoInteractions(variants, eventPublisher);
        }

        @Test
        @DisplayName("setPlain applies the seller's absolute stock to exactly one listing")
        void setPlainAppliesOnce() {
            when(listings.setPlainStock(L1, 12)).thenReturn(1);

            assertThatCode(() -> stock.setPlain(L1, 12)).doesNotThrowAnyException();
            verify(listings).setPlainStock(L1, 12);
            verifyNoInteractions(eventPublisher);
        }

        @ParameterizedTest(name = "count {0}")
        @ValueSource(ints = {0, 2})
        @DisplayName("setPlain throws IllegalStateException when the update count is not exactly 1 — a seller write may fail loudly")
        void setPlainRefusesAnyOtherCount(int count) {
            when(listings.setPlainStock(L1, 12)).thenReturn(count);

            assertThatThrownBy(() -> stock.setPlain(L1, 12))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(L1.toString());
        }

        @Test
        @DisplayName("setVariantStock reports whether the option belongs to that listing")
        void setVariantStockReportsTheCount() {
            UUID opt = variantIdsInLockOrder().get(0);
            Instant now = Instant.parse("2026-09-24T08:00:00Z");
            when(variants.setStock(opt, L1, 4, now)).thenReturn(1);
            when(variants.setStock(opt, L2, 4, now)).thenReturn(0);

            assertThat(stock.setVariantStock(L1, opt, 4, now)).isTrue();
            assertThat(stock.setVariantStock(L2, opt, 4, now)).isFalse();
            verify(listings, never()).recomputeStockTotal(any());
        }

        @Test
        @DisplayName("settle on a plain listing publishes on 0 -> >0 and never recomputes")
        void settlePlainPublishesFromZero() {
            when(listings.stockQtyOf(L1)).thenReturn(5);

            int after = stock.settle(L1, 0, false);

            assertThat(after).isEqualTo(5);
            verify(listings, never()).recomputeStockTotal(any());
            verify(eventPublisher, times(1)).publishEvent(new ListingRestocked(L1));
        }

        @Test
        @DisplayName("settle on a variant listing recomputes the total, THEN reads it, THEN publishes on 0 -> >0")
        void settleVariantRecomputesThenPublishes() {
            when(listings.recomputeStockTotal(L1)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(7);

            int after = stock.settle(L1, 0, true);

            assertThat(after).isEqualTo(7);
            InOrder order = inOrder(listings, eventPublisher);
            order.verify(listings).recomputeStockTotal(L1);
            order.verify(listings).stockQtyOf(L1);
            order.verify(eventPublisher).publishEvent(new ListingRestocked(L1));
        }

        @Test
        @DisplayName("settle does not publish when stock was already above 0")
        void settleNotFromZero() {
            when(listings.recomputeStockTotal(L1)).thenReturn(1);
            when(listings.stockQtyOf(L1)).thenReturn(9);

            assertThat(stock.settle(L1, 3, true)).isEqualTo(9);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("settle does not publish when stock stays at 0")
        void settleStillZero() {
            when(listings.stockQtyOf(L1)).thenReturn(0);

            assertThat(stock.settle(L1, 0, false)).isZero();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("settle throws when a variant listing's total will not recompute, and publishes nothing")
        void settleBrokenTotalThrows() {
            when(listings.recomputeStockTotal(L1)).thenReturn(0);

            assertThatThrownBy(() -> stock.settle(L1, 0, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(L1.toString());
            verify(listings, never()).stockQtyOf(any());
            verifyNoInteractions(eventPublisher);
        }
    }
}
