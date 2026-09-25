package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.catalog.ListingStock;
import com.innbucks.marketplaceservice.catalog.StockLine;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Returns ONE parcel's reserved units to the catalogue when its seller cannot
 * supply them (V12).
 *
 * <p>Order cancel and expiry already return stock for a WHOLE order
 * ({@code OrderService.releaseStockOnce}, guarded by
 * {@code market_order.stock_released}). A parcel released on its own needs its
 * own guard — {@code order_fulfilment.stock_returned} — because the order-level
 * flag says nothing about a single seller's lines, and an order that later
 * expires would otherwise restock these units a second time.
 *
 * <p>The units were genuinely held, so they go back regardless of what the
 * listing looks like now: no status guard, exactly as
 * {@link ListingRepository#restock} documents. Publishing
 * {@link ListingRestocked} on a 0 → &gt;0 move keeps the favourite-alert
 * foundation working from this path too — a seller declining one buyer's
 * parcel genuinely does put stock back on the shelf for everyone else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParcelStockReturner {

    private final MarketOrderItemRepository itemRepository;
    private final ListingStock listingStock;

    /**
     * @return the number of lines returned, 0 when this parcel's stock has
     *         already gone back (a replayed decline is a no-op, not a double
     *         credit to the merchant's shelf)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int returnOnce(OrderFulfilment parcel) {
        if (parcel.isStockReturned()) {
            return 0;
        }
        List<MarketOrderItem> mine = itemRepository.findByOrderId(parcel.getOrderId()).stream()
                .filter(item -> parcel.getMerchantId().equals(item.getMerchantId()))
                .toList();
        // Back to where each line was reserved (the listing, or the option),
        // in the reserve's lock order; ListingStock publishes ListingRestocked
        // for a listing it brings back from 0 and never throws for a return
        // with nowhere to go, which would wedge the decline.
        listingStock.returnAll(mine.stream()
                .map(item -> new StockLine(item.getListingId(), item.getVariantId(),
                        item.getQuantity()))
                .toList());
        parcel.setStockReturned(true);
        log.info("parcel stock returned fulfilmentId={} orderId={} lines={}",
                parcel.getId(), parcel.getOrderId(), mine.size());
        return mine.size();
    }
}
