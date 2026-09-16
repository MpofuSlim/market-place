-- =============================================================================
-- V10: escrow — the merchant settlement ledger and the dispute queue.
--
-- Until now the money side of a marketplace order ENDED at PAID: the payments
-- service collects into the shared platform account under the MKT settlement
-- tag, and "per-merchant split happens marketplace-side" — except nothing
-- marketplace-side did it. There was no record of which seller is owed what,
-- no reason for a seller to be paid only after the buyer got their goods, and
-- no way for a buyer to say they didn't.
--
-- This migration is that record. One settlement row per PARCEL (V9's
-- order_fulfilment — a parcel already is "one seller's share of one order"),
-- opened HELD when the order is paid, released only when the parcel is
-- delivered, frozen by a buyer's dispute, and closed by an operator's payout
-- or refund. "The seller only gets paid when you get your goods" is the
-- product; this table is what makes the sentence true.
--
-- Conventions: money in MINOR units; timestamps TIMESTAMPTZ (UTC Instants);
-- schema Flyway-owned.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. The settlement ledger.
--
-- Keyed by PARCEL, not (order, merchant): the parcel is already unique per
-- (order, merchant) by V9's index, it is the thing whose delivery releases the
-- money, and the unique index here makes opening settlements as replay-safe as
-- opening parcels — a replayed payment confirm can double neither.
--
-- gross/commission/net are all STORED, with the arithmetic CHECK-enforced,
-- because a ledger a reader must re-derive is not a ledger. Commission is 0
-- until the platform decides to charge one (marketplace.settlement.
-- commission-percent) — the columns exist so that decision is a config
-- change, not a migration.
--
-- The delivery fee is NOT in any merchant settlement: sellers ship their own
-- parcels, the flat fee is per ORDER, and splitting one fee across N sellers
-- invents an allocation nobody agreed to. It stays with the platform (fee is
-- 0 by default anyway); refunding it on a full-order refund is part of the
-- operator's manual refund procedure.
-- ---------------------------------------------------------------------------
CREATE TABLE merchant_settlement (
    id               UUID PRIMARY KEY,
    order_id         UUID        NOT NULL REFERENCES market_order (id),
    fulfilment_id    UUID        NOT NULL REFERENCES order_fulfilment (id),
    merchant_id      UUID        NOT NULL,
    status           VARCHAR(16) NOT NULL CHECK (status IN
                         ('HELD', 'RELEASABLE', 'DISPUTED', 'PAID_OUT', 'REFUNDED')),
    gross_cents      BIGINT      NOT NULL CHECK (gross_cents > 0),
    commission_cents BIGINT      NOT NULL DEFAULT 0 CHECK (commission_cents >= 0),
    net_cents        BIGINT      NOT NULL,
    currency         VARCHAR(3)  NOT NULL,
    -- When a seller-closed parcel's grace lapses and the sweeper may release.
    -- NULL on the buyer-confirmed path (released immediately — the buyer's own
    -- word needs no waiting period) and once the row leaves HELD.
    releasable_at    TIMESTAMPTZ,
    released_at      TIMESTAMPTZ,
    paid_out_at      TIMESTAMPTZ,
    -- The operator's payout run reference (bank batch, transfer id) — what a
    -- seller asking "where is my money?" is answered with.
    payout_reference VARCHAR(64),
    refunded_at      TIMESTAMPTZ,
    refund_reference VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_settlement_net CHECK (net_cents = gross_cents - commission_cents)
);

CREATE UNIQUE INDEX uq_settlement_fulfilment  ON merchant_settlement (fulfilment_id);
-- The seller's money view and the operator's per-merchant payout run.
CREATE INDEX idx_settlement_merchant_status   ON merchant_settlement (merchant_id, status, created_at);
-- The release sweeper's scan: HELD rows whose grace has lapsed.
CREATE INDEX idx_settlement_status_releasable ON merchant_settlement (status, releasable_at);
CREATE INDEX idx_settlement_order             ON merchant_settlement (order_id);

-- ---------------------------------------------------------------------------
-- 2. Disputes.
--
-- A dispute freezes ONE parcel's settlement until an operator decides. Its own
-- table rather than columns on the settlement: a dispute has its own life
-- (who raised it, why, who resolved it, with what note) and the settlement row
-- stays pure money-state.
--
-- ONE dispute per parcel, EVER — the unique index is the backstop. A buyer who
-- could re-dispute after an operator released could freeze a seller's money in
-- a loop; a second complaint after resolution is a support matter, not a
-- ledger state. Reasons are a bounded vocabulary (V7 report discipline):
-- free text rides `detail`, sanitized, never in the reason.
-- ---------------------------------------------------------------------------
CREATE TABLE settlement_dispute (
    id              UUID PRIMARY KEY,
    settlement_id   UUID        NOT NULL REFERENCES merchant_settlement (id),
    fulfilment_id   UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    merchant_id     UUID        NOT NULL,
    buyer_uuid      UUID        NOT NULL,
    reason          VARCHAR(32) NOT NULL CHECK (reason IN
                        ('NOT_RECEIVED', 'DAMAGED', 'NOT_AS_DESCRIBED', 'WRONG_ITEM', 'OTHER')),
    detail          VARCHAR(1000),
    status          VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'RELEASED', 'REFUNDED')),
    resolution_note VARCHAR(500),
    resolved_by     UUID,
    created_at      TIMESTAMPTZ NOT NULL,
    resolved_at     TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_dispute_fulfilment    ON settlement_dispute (fulfilment_id);
-- The operator queue: one status, oldest first (FIFO — the buyer who has
-- waited longest is served first, same stance as moderation).
CREATE INDEX idx_dispute_status_created      ON settlement_dispute (status, created_at);
CREATE INDEX idx_dispute_buyer               ON settlement_dispute (buyer_uuid, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. The order journal learns the settlement lifecycle.
--
-- Settlement transitions land in the ORDER's own journal like payment and
-- fulfilment ones (V9's one-journal principle: "what happened to this order"
-- is one question). The kind CHECK from V9 must widen to admit them.
-- ---------------------------------------------------------------------------
ALTER TABLE market_order_event
    DROP CONSTRAINT market_order_event_kind_check;
ALTER TABLE market_order_event
    ADD CONSTRAINT market_order_event_kind_check
        CHECK (kind IN ('PAYMENT', 'FULFILMENT', 'SETTLEMENT'));

-- ---------------------------------------------------------------------------
-- 4. Backfill: every existing parcel of a PAID order gets its settlement row.
--
-- Gross = that merchant's line totals on that order; commission 0 (these rows
-- predate any commission). States follow the parcel honestly:
--   * BUYER-confirmed delivered  -> RELEASABLE now (the buyer's own word is
--     the unconditional release rule; released_at = the delivery moment).
--   * everything else            -> HELD. Seller-closed delivered parcels get
--     releasable_at = delivered_at, so the first sweeper pass after deploy
--     promotes any whose grace has genuinely lapsed — the migration cannot
--     know the configured grace window and does not guess it.
-- ---------------------------------------------------------------------------
INSERT INTO merchant_settlement (id, order_id, fulfilment_id, merchant_id, status,
                                 gross_cents, commission_cents, net_cents, currency,
                                 releasable_at, released_at, created_at, updated_at)
SELECT gen_random_uuid(),
       f.order_id,
       f.id,
       f.merchant_id,
       CASE WHEN f.status = 'DELIVERED' AND f.delivered_by = 'BUYER'
            THEN 'RELEASABLE' ELSE 'HELD' END,
       s.gross_cents,
       0,
       s.gross_cents,
       o.currency,
       CASE WHEN f.status = 'DELIVERED' AND f.delivered_by IS DISTINCT FROM 'BUYER'
            THEN f.delivered_at END,
       CASE WHEN f.status = 'DELIVERED' AND f.delivered_by = 'BUYER'
            THEN f.delivered_at END,
       COALESCE(o.paid_at, o.updated_at),
       COALESCE(o.paid_at, o.updated_at)
  FROM order_fulfilment f
  JOIN market_order o ON o.id = f.order_id
  JOIN (SELECT i.order_id, i.merchant_id, SUM(i.line_total_cents) AS gross_cents
          FROM market_order_item i
         GROUP BY i.order_id, i.merchant_id) s
    ON s.order_id = f.order_id AND s.merchant_id = f.merchant_id
 WHERE o.status = 'PAID'
ON CONFLICT DO NOTHING;
