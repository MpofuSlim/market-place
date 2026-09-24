-- V15: remember whether the buyer was actually told.
--
-- A seller's dispatch, "delivered" or decline sends the buyer an SMS (WhatsApp
-- fallback) AFTER the action commits, and a failed message deliberately never
-- undoes the action. Until now the outcome went to a metric and nowhere else,
-- so a seller whose buyer was never told had no way to find out. The parcel now
-- keeps the LAST such notice: what it was, whether it went out, and when.
--
-- Written only by a bulk UPDATE from the notification listener (never through
-- the entity, so it can never collide with the parcel's optimistic lock).
-- Nothing is backfilled: no outcome was ever recorded, so every existing
-- parcel honestly has none.
ALTER TABLE order_fulfilment
    ADD COLUMN buyer_notice_kind    VARCHAR(24),
    ADD COLUMN buyer_notice_outcome VARCHAR(16),
    ADD COLUMN buyer_notice_at      TIMESTAMPTZ,
    ADD CONSTRAINT chk_fulfilment_buyer_notice CHECK (
        (buyer_notice_kind IS NULL AND buyer_notice_outcome IS NULL AND buyer_notice_at IS NULL)
        OR (buyer_notice_kind IS NOT NULL AND buyer_notice_outcome IS NOT NULL
            AND buyer_notice_at IS NOT NULL));
