-- =============================================================================
-- V5: verified-purchase listing reviews.
--
-- A review may only be written by a buyer with a PAID order containing the
-- listing (the app-level eligibility gate); order_id records WHICH order
-- qualified the reviewer. merchant_id is denormalized from the listing at
-- write time so merchant-level rating aggregates never join through listing.
--
-- One review per buyer per listing — the unique index is the DB backstop
-- under the app's existsBy check (same belt-and-braces stance as the order
-- idempotency key).
-- =============================================================================
CREATE TABLE listing_review (
    id          UUID PRIMARY KEY,
    listing_id  UUID          NOT NULL REFERENCES listing (id),
    merchant_id UUID          NOT NULL,
    buyer_uuid  UUID          NOT NULL,
    order_id    UUID          NOT NULL,
    rating      INTEGER       NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     VARCHAR(1000),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_review_buyer_listing ON listing_review (buyer_uuid, listing_id);
CREATE INDEX idx_review_listing  ON listing_review (listing_id);
CREATE INDEX idx_review_merchant ON listing_review (merchant_id);

-- Denormalized rating aggregates on the listing row so catalog reads carry
-- ratingAvg/reviewCount with ZERO extra queries. Updated ATOMICALLY via bulk
-- UPDATE in the same transaction as every review write — never read-modify-
-- write through the entity (same discipline as stock_qty).
ALTER TABLE listing
    ADD COLUMN rating_sum   BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;
