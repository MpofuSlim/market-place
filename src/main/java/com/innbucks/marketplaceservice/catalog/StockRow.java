package com.innbucks.marketplaceservice.catalog;

/**
 * What a stock movement reads from the listing row it has just locked
 * ({@link ListingRepository#lockForStock}): whether it can still be sold,
 * which stock model it uses, and the stock before the movement (the exact
 * "before" a 0 → &gt;0 restock check needs, read under the lock rather than
 * racing it).
 */
public interface StockRow {

    String getStatus();

    Boolean getHasVariants();

    Integer getStockQty();
}
