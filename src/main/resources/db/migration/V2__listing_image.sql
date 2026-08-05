-- =============================================================================
-- V2: listing images (event-service banner design).
--
-- BYTEA, not a length-capped varbinary: Postgres BYTEA has no size limit that
-- would truncate a real-world product image. Bytes are served raw via the
-- public GET /marketplace/catalog/{id}/image endpoint with the stored
-- content type; the entity maps the bytes lazily so list endpoints never pull
-- them. image_content_type doubles as the has-image marker (it is only ever
-- written alongside the bytes and cleared with them).
-- =============================================================================
ALTER TABLE listing
    ADD COLUMN image_bytes        BYTEA,
    ADD COLUMN image_content_type VARCHAR(64);
