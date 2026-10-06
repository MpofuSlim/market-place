-- =============================================================================
-- V25: stored renditions of listing images.
--
-- The public image endpoints (GET /marketplace/catalog/{id}/image and
-- /{id}/images/{imageId}, optional ?w=120|240|480|960) used to load the full
-- original BYTEA on every request and, for ?w=, decode, scale and re-encode it
-- every time. Each servable rendition of an image is now stored here ONCE: at
-- upload, or on the first request for an image stored before this migration.
-- A request then reads one small row by primary key and never the original.
--
-- variant           'original', 'w120', 'w240', 'w480' or 'w960'.
-- source_created_at listing_image.created_at of the bytes this row was made
--                   from. A primary-image replace overwrites the row in place
--                   (same id) and stamps a new created_at, so a row whose stamp
--                   no longer matches describes old bytes and is never served —
--                   which keeps a replace by a pod still running the previous
--                   image (it knows nothing of this table) correct as well.
-- resized           false = this request is answered with the ORIGINAL bytes
--                   (no resize applies: WebP, already narrow, over the pixel
--                   budget, unreadable header, too big to decode). bytes is
--                   then NULL rather than a second copy of the original.
-- etag              SHA-256 (hex) of the bytes the request is answered with.
--
-- Safe on a live database: a new, empty table. The foreign key's validation
-- scans nothing and its brief SHARE ROW EXCLUSIVE lock on listing_image does
-- not block reads. Nothing is backfilled here — rows for existing images are
-- written by the first request for each (image, variant). A rollback to the
-- previous image is safe: it never reads this table, and ON DELETE CASCADE
-- keeps it clean when that image deletes a gallery row.
-- =============================================================================
CREATE TABLE listing_image_variant (
    image_id          UUID        NOT NULL REFERENCES listing_image (id) ON DELETE CASCADE,
    variant           VARCHAR(16) NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL,
    resized           BOOLEAN     NOT NULL,
    content_type      VARCHAR(64) NOT NULL,
    bytes             BYTEA,
    width             INTEGER,
    height            INTEGER,
    byte_size         INTEGER     NOT NULL,
    etag              VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (image_id, variant),
    CONSTRAINT chk_listing_image_variant_name
        CHECK (variant IN ('original', 'w120', 'w240', 'w480', 'w960')),
    CONSTRAINT chk_listing_image_variant_bytes
        CHECK ((resized AND bytes IS NOT NULL) OR (NOT resized AND bytes IS NULL))
);
