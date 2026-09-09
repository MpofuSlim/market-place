-- =============================================================================
-- V8: seller trust record + SUPER_ADMIN approval queue.
--
-- Until now a "seller" was nothing but a merchant_id UUID stamped on a listing
-- from the caller's JWT. There was no record to hang a decision on, so the
-- platform could neither vet a seller nor tell a buyer it had: a shopper could
-- not distinguish a long-standing merchant from one that signed up an hour ago.
--
-- Keyed by merchant_id (not a surrogate id): a merchant IS the seller here, the
-- id is already the fleet-wide identity carried on every listing, and a PK on
-- it makes "one trust record per merchant" true by construction rather than by
-- a uniqueness index someone can forget.
-- =============================================================================
CREATE TABLE marketplace_seller (
    merchant_id   UUID PRIMARY KEY,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN
                      ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    -- Shown on the buyer-facing seller badge. Nullable because marketplace-service
    -- has NO source for a merchant's trading name today — it holds ids, not
    -- names, and there is no loyalty/user lookup in this repo. An admin sets it
    -- when they approve; resolving it automatically from InnRewards
    -- (merchants.name) is a clean follow-up that needs an S2S client this
    -- service does not yet have.
    display_name  VARCHAR(120),
    decided_by    UUID,
    decision_note VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL,
    decided_at    TIMESTAMPTZ
);

-- Serves the queue's default read: one status, oldest first (FIFO), mirroring
-- idx_report_status_created.
CREATE INDEX idx_seller_status_created ON marketplace_seller (status, created_at);

-- Backfill every merchant that has already listed something, as PENDING.
--
-- PENDING and not APPROVED, deliberately. None of these merchants was ever
-- vetted — marking them APPROVED would put a "verified" badge on the whole
-- existing catalogue on day one, which is the platform asserting something it
-- has not actually done. Same call as loyalty's fee_waived backfill: the
-- backlog IS the point, and it lands in the admin queue where it belongs.
--
-- Nobody is blocked by this: PENDING sellers can still publish (see
-- SellerStatus.canPublish) — only REJECTED and SUSPENDED cannot.
--
-- created_at is the merchant's FIRST listing, so the queue's oldest-first order
-- reflects how long they have actually been trading here rather than the moment
-- this migration ran.
INSERT INTO marketplace_seller (merchant_id, status, created_at)
SELECT merchant_id, 'PENDING', MIN(created_at)
FROM listing
GROUP BY merchant_id
ON CONFLICT (merchant_id) DO NOTHING;
