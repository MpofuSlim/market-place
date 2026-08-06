-- =============================================================================
-- V6: buyer favorites (wishlist).
--
-- Composite PK makes the add idempotent at the DB level (the app inserts with
-- ON CONFLICT DO NOTHING). listing_id is indexed for the restock-alert
-- foundation's "how many favoriters" count and a future merchant-side
-- popularity surface. Deliberately NO audit rows for favorites: high-volume,
-- low-sensitivity — see CLAUDE.md.
-- =============================================================================
CREATE TABLE listing_favorite (
    buyer_uuid UUID        NOT NULL,
    listing_id UUID        NOT NULL REFERENCES listing (id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (buyer_uuid, listing_id)
);

CREATE INDEX idx_favorite_listing ON listing_favorite (listing_id);
