-- V16: seller alerts that fire once, and a buyer who can call off a parcel.
--
-- Conventions: timestamps TIMESTAMPTZ (UTC Instants); schema Flyway-owned.

-- ---------------------------------------------------------------------------
-- 1. "Waiting at your counter for over N days" is said ONCE per parcel.
--
-- The overdue-collection sweep runs daily; without a marker it would repeat
-- the same alert every morning for as long as the goods sat there, and a bell
-- that says the same thing every day is a bell people stop reading. The sweep
-- CLAIMS the parcel (sets this) before it sends, so a crash between the two
-- loses an alert rather than doubling one — at most once, by design.
-- Nothing is backfilled: every collection already overdue is alerted on the
-- first sweep after deploy, which is the point.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment
    ADD COLUMN collection_overdue_alerted_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- 2. Who ended an UNFULFILLED parcel.
--
-- Until now only the seller could (V12: "I cannot supply this", or a
-- collection never picked up). A buyer may now cancel a paid parcel the seller
-- has not yet sent, and the money turns around exactly the same way — but the
-- two are different facts on every screen: the seller's portal must not say
-- "you could not supply this" about an order the buyer changed their mind on,
-- and the buyer's app must not blame the seller for their own cancellation.
--
-- Every UNFULFILLED row that exists today was the seller's (the buyer path is
-- new in this release), so the backfill states a fact, not a guess. The CHECK
-- keeps it complete-or-absent: a closed-without-arriving parcel always names
-- who closed it, and an open or delivered one never does. The explicit
-- IS NOT NULL is load-bearing: `NULL IN (...)` is UNKNOWN, and a CHECK that
-- evaluates to UNKNOWN passes — without it an UNFULFILLED row naming nobody
-- would be accepted.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment
    ADD COLUMN unfulfilled_by VARCHAR(16);

UPDATE order_fulfilment SET unfulfilled_by = 'SELLER' WHERE status = 'UNFULFILLED';

ALTER TABLE order_fulfilment
    ADD CONSTRAINT chk_fulfilment_unfulfilled_by CHECK (
        (status = 'UNFULFILLED' AND unfulfilled_by IS NOT NULL
            AND unfulfilled_by IN ('SELLER', 'BUYER'))
        OR (status <> 'UNFULFILLED' AND unfulfilled_by IS NULL));

-- The sweep's lookup: open collections set aside at the counter, not yet
-- alerted. Partial, so it indexes only the handful of rows it can ever match.
CREATE INDEX idx_fulfilment_collection_overdue
    ON order_fulfilment (dispatched_at)
    WHERE status = 'DISPATCHED' AND collection_overdue_alerted_at IS NULL;
