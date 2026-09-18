package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ListingRepository listingRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        for (MarketOrderItem item : mine) {
            Integer before = listingRepository.stockQtyOf(item.getListingId());
            listingRepository.restock(item.getListingId(), item.getQuantity());
            if (before != null && before == 0 && item.getQuantity() > 0) {
                eventPublisher.publishEvent(new ListingRestocked(item.getListingId()));
            }
        }
        parcel.setStockReturned(true);
        log.info("parcel stock returned fulfilmentId={} orderId={} lines={}",
                parcel.getId(), parcel.getOrderId(), mine.size());
        return mine.size();
    }
}
