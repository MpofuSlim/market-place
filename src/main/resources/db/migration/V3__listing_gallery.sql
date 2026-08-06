-- =============================================================================
-- V3: multi-image listing gallery.
--
-- Replaces the V2 single-image columns on listing with a listing_image table:
-- ONE PRIMARY image per listing (partial unique index is the DB backstop for
-- the app-level "atomic swap" promotion logic) plus up to 9 additional images
-- (the 10-image cap is app-enforced — a DB trigger for a soft product cap
-- would be overkill).
--
-- BYTEA for the same reason as V2: no size cap that would truncate a real
-- product photo. Bytes are served raw via the public
-- GET /marketplace/catalog/{listingId}/images/{imageId} (any image) and
-- GET /marketplace/catalog/{listingId}/image (the primary — unchanged V2
-- contract); the entity maps bytes lazily and every metadata read goes
-- through a bytes-free projection.
-- =============================================================================
CREATE TABLE listing_image (
    id           UUID PRIMARY KEY,
    listing_id   UUID        NOT NULL REFERENCES listing (id),
    image_bytes  BYTEA       NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    is_primary   BOOLEAN     NOT NULL DEFAULT FALSE,
    position     INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL
);

-- ONE primary per listing, enforced by the database no matter what the app
-- layer does. Promotion swaps run demote-then-mark in that order so this
-- index never sees two primaries inside one transaction.
CREATE UNIQUE INDEX uq_listing_image_primary ON listing_image (listing_id) WHERE is_primary;
CREATE INDEX idx_listing_image_listing ON listing_image (listing_id);

-- Data migration: every existing single-image listing keeps its image as the
-- gallery's PRIMARY row. content_type is only ever written alongside the
-- bytes (V2 invariant), so the COALESCE is a belt for hand-edited rows —
-- image/jpeg keeps such a row servable rather than failing the migration.
INSERT INTO listing_image (id, listing_id, image_bytes, content_type, is_primary, position, created_at)
SELECT gen_random_uuid(), id, image_bytes, COALESCE(image_content_type, 'image/jpeg'), TRUE, 0, updated_at
  FROM listing
 WHERE image_bytes IS NOT NULL;

ALTER TABLE listing
    DROP COLUMN image_bytes,
    DROP COLUMN image_content_type;
