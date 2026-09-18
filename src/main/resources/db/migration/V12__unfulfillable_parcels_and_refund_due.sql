-- =============================================================================
-- V12: the parcel that never completes, and the money behind it.
--
-- V9 gave a parcel three states — PREPARING, DISPATCHED, DELIVERED — and no way
-- to say "I can't send this". V10 then put the buyer's money behind delivery.
-- Together those made a silent trap: a seller who is out of stock, or who
-- simply goes quiet, leaves the parcel open forever, and the settlement behind
-- it sits HELD forever with it. The release sweeper cannot rescue it — it
-- matches rows whose releasable_at has lapsed, and a parcel that was never
-- delivered has no releasable_at at all — so the buyer's money is never
-- released, never refunded, and nothing anywhere notices its age.
--
-- This migration gives that state two exits: one the seller can take
-- deliberately, and one an operator can see.
--
-- Conventions: money in MINOR units; timestamps TIMESTAMPTZ (UTC Instants);
-- schema Flyway-owned.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. A parcel can end without arriving.
--
-- UNFULFILLED, not CANCELLED: "cancelled" is what a BUYER does to an order
-- before paying, and market_order already uses that word for it. This is the
-- seller saying they cannot supply goods already paid for — a different fact,
-- with a different consequence (the buyer is owed money back), and it deserves
-- a word that cannot be confused with the other.
--
-- Reachable ONLY from PREPARING, enforced in FulfilmentStateMachine. Once a
-- parcel is DISPATCHED the goods are with a courier and "I can't fulfil this"
-- is no longer the truth — that is a delivery failure, which is what the
-- dispute path exists for.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment
    DROP CONSTRAINT order_fulfilment_status_check;
ALTER TABLE order_fulfilment
    ADD CONSTRAINT order_fulfilment_status_check
        CHECK (status IN ('PREPARING', 'DISPATCHED', 'DELIVERED', 'UNFULFILLED'));

ALTER TABLE order_fulfilment
    ADD COLUMN unfulfilled_at     TIMESTAMPTZ,
    -- The seller's own words, shown to the buyer. Sanitized free text: the
    -- buyer is about to lose the thing they paid for, and "out of stock" or
    -- "damaged in storage" is the difference between an explanation and a
    -- silent disappearance.
    ADD COLUMN unfulfilled_reason VARCHAR(255),
    -- Per-parcel double-return guard, the sibling of market_order.stock_released
    -- one level up. That flag is per ORDER and is owned by cancel/expiry; a
    -- parcel released on its own needs its own flag, or an order later expiring
    -- would restock this parcel's units a second time.
    ADD COLUMN stock_returned     BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- 2. Money the platform owes back but has not yet sent.
--
-- REFUND_DUE is the mirror image of RELEASABLE, and it exists for the same
-- reason that one does: this service moves no money, so the ledger needs a
-- state for "decided, not yet transferred". Collapsing it into REFUNDED would
-- record a refund the operator has not made — and REFUNDED carries a
-- refund_reference precisely because it means the money actually left.
--
--   seller's money:  HELD -> RELEASABLE  -> PAID_OUT   (payout run)
--   buyer's money:   HELD -> REFUND_DUE  -> REFUNDED   (refund, per parcel)
--   contested:       HELD -> DISPUTED    -> either     (operator decides)
--
-- Deliberately NOT reachable from DISPUTED: a dispute is resolved by an
-- operator who is already looking at it and already supplies the reference, so
-- routing it through a queue of things to decide would be a second decision
-- about a decision already made.
-- ---------------------------------------------------------------------------
ALTER TABLE merchant_settlement
    DROP CONSTRAINT merchant_settlement_status_check;
ALTER TABLE merchant_settlement
    ADD CONSTRAINT merchant_settlement_status_check
        CHECK (status IN ('HELD', 'RELEASABLE', 'DISPUTED', 'PAID_OUT', 'REFUNDED', 'REFUND_DUE'));

ALTER TABLE merchant_settlement
    ADD COLUMN refund_due_at TIMESTAMPTZ;

-- The operator's refund queue: everything owed back, oldest first, because a
-- buyer waiting on money the platform already holds is the one queue that must
-- never be served newest-first.
CREATE INDEX idx_settlement_refund_due ON merchant_settlement (status, refund_due_at)
    WHERE status = 'REFUND_DUE';

-- The stale-escrow sweep's scan: HELD rows by age. Partial, because every other
-- status is either already terminal or already has its own timer.
CREATE INDEX idx_settlement_held_age ON merchant_settlement (created_at)
    WHERE status = 'HELD';

-- ---------------------------------------------------------------------------
-- 3. No backfill, deliberately.
--
-- Existing HELD settlements stay HELD and existing parcels stay in whatever
-- state they are in. Marking any of them UNFULFILLED or REFUND_DUE would be
-- this migration asserting a seller's intent it never observed — the same call
-- V9 made backfilling parcels as PREPARING rather than DELIVERED. What these
-- rows get instead is VISIBILITY: the stale sweep introduced with this
-- migration will surface any of them that are genuinely overdue, and an
-- operator decides one at a time.
-- ---------------------------------------------------------------------------
