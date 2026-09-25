-- =============================================================================
-- V19: product variants - one listing, several sizes / colours, each with its
-- own stock and, optionally, a price of its own.
--
-- A listing had ONE price and ONE stock count, so a seller of shoes made one
-- listing per size and a buyer could not choose a size on a product page.
--
-- Model (CLAUDE.md "Product variants (V19)"):
--  * A listing may name up to two option axes and own 1..N listing_variant
--    rows. has_variants is the discriminator every stock statement guards on.
--  * For a variant listing, listing_variant.stock_qty is the truth and
--    listing.stock_qty is a DERIVED total: recomputed from the variants, under
--    the listing row lock, in the same transaction as every variant movement.
--    Browse inStock, card stockQty and restock detection keep reading the
--    listing row unchanged.
--  * listing.price_cents stays the listing price and is always the LOWEST
--    option price (the service refuses a set where it is not). A variant's
--    price_cents is an optional surcharge; NULL = the listing price.
--
-- Deploy safety: every change is ADDITIVE for the previous image - new
-- tables, nullable or defaulted columns, CHECKs every existing row and every
-- pre-V19 INSERT satisfies. cart_item is deliberately NOT altered: variant
-- lines live in their own table so the old image's
-- ON CONFLICT (buyer_uuid, listing_id) upserts keep their arbiter through the
-- rolling restart. V19MigrationIT replays the old image's statements here.
--
-- Nothing is backfilled: no variant was ever observed, so every existing
-- listing, cart line and order line is correctly variant-less. Every CHECK over
-- nullable columns names IS [NOT] NULL explicitly (the V16/V17 lesson).
-- =============================================================================

-- 1. Option axes on the listing.
ALTER TABLE listing
    ADD COLUMN has_variants BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN option1_name VARCHAR(30),
    ADD COLUMN option2_name VARCHAR(30);

-- has_variants is NOT NULL, so both branches are definite. option2 without
-- option1 is impossible: option1 is required whenever has_variants, and both
-- are NULL otherwise.
ALTER TABLE listing ADD CONSTRAINT chk_listing_variant_axes CHECK (
       (has_variants = FALSE AND option1_name IS NULL AND option2_name IS NULL)
    OR (has_variants = TRUE  AND option1_name IS NOT NULL));

-- 2. Variants. A child of listing, like listing_delivery_town (the CASCADE is a
--    backstop: listings are soft-deleted). Variants are HARD-deleted when a
--    seller removes one: order lines snapshot the label and carry variant_id as
--    provenance with no FK, and cart lines carry no FK to this table, so a
--    removed option shows as VARIANT_UNAVAILABLE instead of vanishing.
--    option_key = lower(value1) || ',' || lower(value2 or ''), computed by the
--    service (values can never contain ','). It is the ONE definition of
--    "the same option", so Java and the DB collation can never disagree.
--    The uniqueness is DEFERRABLE INITIALLY DEFERRED, so an editor save that
--    renames or swaps options (M->L while L->M) is checked at commit, not
--    per row. The editor is serialised by the listing row lock, so a violation
--    at commit is unreachable except through a bug.
CREATE TABLE listing_variant (
    id             UUID          PRIMARY KEY,
    listing_id     UUID          NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    option1_value  VARCHAR(40)   NOT NULL,
    option2_value  VARCHAR(40),
    option_key     VARCHAR(200)  NOT NULL,
    price_cents    BIGINT,
    stock_qty      INTEGER       NOT NULL,
    position       INTEGER       NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_listing_variant_stock    CHECK (stock_qty >= 0),
    CONSTRAINT chk_listing_variant_price    CHECK (price_cents IS NULL OR price_cents > 0),
    CONSTRAINT chk_listing_variant_position CHECK (position >= 0),
    CONSTRAINT uq_listing_variant_option_key UNIQUE (listing_id, option_key)
        DEFERRABLE INITIALLY DEFERRED
);
-- The constraint's index leads on listing_id, so it also serves every
-- listing_id = / IN (...) read. No separate index.

-- 3. Cart lines that name a variant. A SIBLING of cart_item, not a change to it
--    (see header). The same shape as V9: quantities only, no price, no stock.
--    listing_id is kept so the pricer and DELETE-by-listing work without the
--    variant row. It is FKed like cart_item's. variant_id has NO FK, on
--    purpose: a seller removing an option must leave the shopper's line
--    visible with a VARIANT_UNAVAILABLE issue (the V9 "never drop a line
--    silently" rule).
CREATE TABLE cart_variant_item (
    buyer_uuid  UUID        NOT NULL,
    variant_id  UUID        NOT NULL,
    listing_id  UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    quantity    INTEGER     NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (buyer_uuid, variant_id),
    CONSTRAINT chk_cart_variant_item_quantity CHECK (quantity > 0)
);
CREATE INDEX idx_cart_variant_item_buyer ON cart_variant_item (buyer_uuid, added_at DESC);

-- 4. Order lines SNAPSHOT the variant (the title_snapshot discipline). There is
--    no FK, like market_order_item.listing_id and merchant_id. listing_id stays
--    the PARENT listing, which the review gate, restock and reports key on. The
--    label is kept out of title_snapshot, because a 160-character title plus a
--    label would overflow VARCHAR(160). Label <= 40 + 3 + 40 characters.
ALTER TABLE market_order_item
    ADD COLUMN variant_id    UUID,
    ADD COLUMN variant_label VARCHAR(100);
ALTER TABLE market_order_item ADD CONSTRAINT chk_order_item_variant_snapshot CHECK (
       (variant_id IS NULL     AND variant_label IS NULL)
    OR (variant_id IS NOT NULL AND variant_label IS NOT NULL));
