package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.catalog.ListingStock;
import com.innbucks.marketplaceservice.catalog.StockLine;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ParcelStockReturner} — the return of ONE parcel's units when its
 * seller declines it or its buyer cancels it (V12, V16), now routed through
 * {@link ListingStock} so an option line goes back to its option (V19).
 *
 * <p>Three rules: only the declining seller's lines of a multi-seller order go
 * back; each line keeps its {@code variantId}, because an option line credited
 * to the listing would be refused by the plain statement and lost; and the
 * per-parcel {@code stock_returned} guard makes a replay a no-op, so a
 * repeated decline can never put the same units on the shelf twice.
 */
class ParcelStockReturnerTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();

    private static final UUID LANTERN = UUID.randomUUID();
    private static final UUID SANDALS = UUID.randomUUID();
    private static final UUID SANDALS_SIZE_42 = UUID.randomUUID();
    private static final UUID KETTLE = UUID.randomUUID();

    private MarketOrderItemRepository itemRepository;
    private ListingStock listingStock;
    private ParcelStockReturner returner;

    @BeforeEach
    void setUp() {
        itemRepository = mock(MarketOrderItemRepository.class);
        listingStock = mock(ListingStock.class);
        returner = new ParcelStockReturner(itemRepository, listingStock);
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                item(LANTERN, null, null, MERCHANT_A, "Solar Lantern 20W", 2),
                item(KETTLE, null, null, MERCHANT_B, "Electric Kettle 1.7L", 1),
                item(SANDALS, SANDALS_SIZE_42, "42 - Tan", MERCHANT_A, "Leather Sandals", 3)));
    }

    private static MarketOrderItem item(UUID listingId, UUID variantId, String variantLabel,
                                        UUID merchantId, String title, int qty) {
        return MarketOrderItem.builder().id(UUID.randomUUID()).orderId(ORDER_ID)
                .listingId(listingId).variantId(variantId).variantLabel(variantLabel)
                .merchantId(merchantId).titleSnapshot(title).unitPriceCents(1550)
                .quantity(qty).lineTotalCents(1550L * qty).build();
    }

    private static OrderFulfilment parcel(UUID merchantId, boolean stockReturned) {
        Instant now = Instant.parse("2026-09-24T08:00:00Z");
        return OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(merchantId)
                .status(FulfilmentStatus.PREPARING).stockReturned(stockReturned)
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    @SuppressWarnings("unchecked")
    private List<StockLine> returnedLines() {
        ArgumentCaptor<Collection<StockLine>> lines = ArgumentCaptor.forClass(Collection.class);
        verify(listingStock).returnAll(lines.capture());
        return List.copyOf(lines.getValue());
    }

    @Test
    @DisplayName("Only THIS seller's lines go back, each as a StockLine carrying its option, and the parcel is marked returned")
    void onlyThisSellersLinesGoBack() {
        OrderFulfilment parcel = parcel(MERCHANT_A, false);

        int returned = returner.returnOnce(parcel);

        assertThat(returned).isEqualTo(2);
        assertThat(returnedLines()).containsExactlyInAnyOrder(
                new StockLine(LANTERN, null, 2),
                new StockLine(SANDALS, SANDALS_SIZE_42, 3));
        assertThat(parcel.isStockReturned()).isTrue();
    }

    @Test
    @DisplayName("The other seller's parcel of the same order returns only its own lines")
    void theOtherSellerReturnsOnlyTheirs() {
        OrderFulfilment parcel = parcel(MERCHANT_B, false);

        assertThat(returner.returnOnce(parcel)).isEqualTo(1);
        assertThat(returnedLines()).containsExactly(new StockLine(KETTLE, null, 1));
        assertThat(parcel.isStockReturned()).isTrue();
    }

    @Test
    @DisplayName("A parcel whose stock already went back is a no-op returning 0 — nothing is read and nothing is credited")
    void alreadyReturnedIsANoOp() {
        OrderFulfilment parcel = parcel(MERCHANT_A, true);

        assertThat(returner.returnOnce(parcel)).isZero();
        verifyNoInteractions(itemRepository, listingStock);
        assertThat(parcel.isStockReturned()).isTrue();
    }

    @Test
    @DisplayName("A replayed decline on the same parcel credits the shelf exactly once")
    void aReplayCreditsOnce() {
        OrderFulfilment parcel = parcel(MERCHANT_A, false);

        assertThat(returner.returnOnce(parcel)).isEqualTo(2);
        assertThat(returner.returnOnce(parcel)).isZero();

        verify(listingStock, times(1)).returnAll(any());
        verify(itemRepository, times(1)).findByOrderId(ORDER_ID);
    }
}
