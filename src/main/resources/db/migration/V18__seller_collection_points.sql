-- =============================================================================
-- V18: where a buyer actually collects.
--
-- COLLECTION is the default way to receive goods, yet the platform has never
-- known WHERE a seller hands them over. The only place a buyer could learn it
-- was the seller's free-text dispatch note, written after payment, so a
-- shopper deciding whether to buy could not see where they would have to go,
-- and nothing could say "collectable in my town".
--
-- A seller now keeps up to ten structured collection points: a name, a town
-- from the same controlled list delivery uses, an address, optional opening
-- hours and an optional map pin. Every listing of the seller is collectable at
-- every one of their points (no per-listing subset: a seller's counter sells
-- everything the seller sells).
--
-- Conventions: timestamps TIMESTAMPTZ (UTC Instants), EXCEPT opening hours,
-- which are market-local wall-clock TIMEs of day and are never converted;
-- coordinates NUMERIC(9,6) as V14; every CHECK over nullable columns names
-- its IS [NOT] NULL explicitly (the V16/V17 lesson: a branch that compares a
-- NULL is UNKNOWN, and a CHECK that is UNKNOWN passes).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. The seller's points.
--
-- Hard-deleted, not deactivated: an order never reads through a point (see 3),
-- so removing one cannot move a pending collection, and a deactivated row
-- would only be a second meaning of "gone" for every query to remember.
-- ---------------------------------------------------------------------------
CREATE TABLE seller_collection_point (
    id           UUID          PRIMARY KEY,
    merchant_id  UUID          NOT NULL,
    name         VARCHAR(80)   NOT NULL,
    town_code    VARCHAR(40)   NOT NULL REFERENCES delivery_town (code),
    line1        VARCHAR(160)  NOT NULL,
    line2        VARCHAR(160),
    area         VARCHAR(80),
    landmark     VARCHAR(160),
    -- A counter's number, which may be a landline: any valid E.164 number.
    phone        VARCHAR(20),
    -- What structured hours cannot say ("Closed on public holidays").
    hours_note   VARCHAR(160),
    latitude     NUMERIC(9, 6),
    longitude    NUMERIC(9, 6),
    -- Exactly one default per seller whenever the seller has any point; it is
    -- what a buyer who does not choose collects from.
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_collection_point_pin CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);

CREATE INDEX idx_collection_point_merchant ON seller_collection_point (merchant_id);
-- "collectsIn": the catalogue's EXISTS correlates on merchant and town.
CREATE INDEX idx_collection_point_town ON seller_collection_point (town_code, merchant_id);
-- The DB backstop for "exactly one default": at most one row per seller may
-- carry the flag. "At least one" is kept by the service (promote on delete).
CREATE UNIQUE INDEX uq_collection_point_default
    ON seller_collection_point (merchant_id) WHERE is_default;

-- ---------------------------------------------------------------------------
-- 2. Weekly opening hours, market-local.
--
-- One row per open interval: a shop that closes for lunch has two rows for
-- that day. No rows means the seller has not said, which is different from
-- "closed" and is rendered as such. ISO day numbers (Monday = 1).
-- ---------------------------------------------------------------------------
CREATE TABLE seller_collection_point_hours (
    point_id     UUID      NOT NULL REFERENCES seller_collection_point (id) ON DELETE CASCADE,
    day_of_week  SMALLINT  NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at     TIME      NOT NULL,
    closes_at    TIME      NOT NULL,
    PRIMARY KEY (point_id, day_of_week, opens_at),
    CONSTRAINT chk_collection_point_hours_order CHECK (opens_at < closes_at)
);

-- ---------------------------------------------------------------------------
-- 3. Where each seller's goods on an order are collected: a SNAPSHOT.
--
-- One row per (order, seller) for COLLECTION orders whose seller had a point
-- at order time; the market_order_delivery_fee pattern. The fields are
-- COPIED, and collection_point_id is provenance only (no FK): a seller who
-- renames, moves or deletes a point must not silently move goods a buyer has
-- already been told to fetch from somewhere else. Opening hours are NOT
-- copied on purpose: they answer "when can I come", which is a question about
-- today, so views read them live through the provenance id while the point
-- exists.
--
-- A seller with no point at order time gets no row: collection is then
-- "arranged with the seller", exactly what every COLLECTION order meant
-- before this migration. Nothing is backfilled for the same reason.
-- ---------------------------------------------------------------------------
CREATE TABLE market_order_collection_point (
    order_id             UUID          NOT NULL REFERENCES market_order (id) ON DELETE CASCADE,
    merchant_id          UUID          NOT NULL,
    collection_point_id  UUID          NOT NULL,
    name                 VARCHAR(80)   NOT NULL,
    town_code            VARCHAR(40)   NOT NULL,
    line1                VARCHAR(160)  NOT NULL,
    line2                VARCHAR(160),
    area                 VARCHAR(80),
    landmark             VARCHAR(160),
    phone                VARCHAR(20),
    latitude             NUMERIC(9, 6),
    longitude            NUMERIC(9, 6),
    PRIMARY KEY (order_id, merchant_id),
    CONSTRAINT chk_order_collection_point_pin CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);
