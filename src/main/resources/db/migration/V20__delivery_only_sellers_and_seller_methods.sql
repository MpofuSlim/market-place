-- V20: a seller may be DELIVERY-ONLY, and every order records each seller's
-- delivery method.
--
-- EXPAND ONLY. Nothing here backfills or validates live data, so it cannot
-- fail on an existing row, and the V19 image keeps working against it during
-- the rolling restart (every new column has a default or is nullable, and no
-- statement the V19 image runs names a new NOT NULL column).
--
-- 1. marketplace_seller.collection_enabled. TRUE is what every seller is
--    today: collection has always been allowed, with "arranged with the
--    seller" standing in when they list no collection point. FALSE is a
--    delivery-only seller. The DEFAULT is load-bearing:
--    MarketplaceSellerRepository.insertIfAbsent names (merchant_id, status,
--    created_at) only, from every image, and a missing value must read as
--    "collects". A constant default is a metadata-only change on PG16, so the
--    ALTER rewrites no rows.
ALTER TABLE marketplace_seller
    ADD COLUMN collection_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN collection_updated_at TIMESTAMPTZ,
    ADD COLUMN collection_updated_by UUID;

-- 2. Each seller's delivery method on each order, written at order creation
--    for EVERY seller in the basket. Not an exception list: the order view
--    needs each seller's method before payment, when there are no parcels
--    yet, and neither sibling table is a complete record of it
--    (market_order_delivery_fee has no row for a collecting seller or for a
--    pre-V14 order; market_order_collection_point has none for a collecting
--    seller with no point). Orders created by the V19 image during the
--    rollout have no rows here; every such order is uniform, so its
--    market_order.delivery_method is exact for each of its sellers.
CREATE TABLE market_order_seller (
    order_id        UUID        NOT NULL REFERENCES market_order (id) ON DELETE CASCADE,
    merchant_id     UUID        NOT NULL,
    delivery_method VARCHAR(16) NOT NULL,
    PRIMARY KEY (order_id, merchant_id),
    CONSTRAINT chk_order_seller_method CHECK (delivery_method IN ('DELIVERY', 'COLLECTION'))
);

-- 3. The parcel's own method, copied from its seller's row when the order is
--    paid and never changed after. NULLABLE for now: the V19 image's
--    openIfAbsent does not name it, and a parcel it opens during the rollout
--    must still insert. A later migration backfills the stragglers and sets
--    NOT NULL once no V19 replica can be running.
--
--    Each CHECK spells out its NULL case: a CHECK that evaluates to UNKNOWN
--    passes, which is how V13's payout CHECK and the first draft of V16's
--    unfulfilled_by CHECK let rows through (see V17).
ALTER TABLE order_fulfilment ADD COLUMN delivery_method VARCHAR(16);
ALTER TABLE order_fulfilment
    ADD CONSTRAINT chk_fulfilment_delivery_method
        CHECK (delivery_method IS NULL OR delivery_method IN ('DELIVERY', 'COLLECTION')),
    -- A parcel the buyer collects carries no delivery fee: the fee is what the
    -- buyer paid a seller to bring it.
    ADD CONSTRAINT chk_fulfilment_collection_no_fee
        CHECK (delivery_method IS NULL OR delivery_method <> 'COLLECTION' OR delivery_fee_cents = 0);
