-- =============================================================================
-- V13: where a seller's money actually goes.
--
-- V10 built the escrow ledger and V12 the refund side, so the platform now
-- knows precisely WHO is owed and HOW MUCH. It has never known WHERE to send
-- it. GET /marketplace/settlements/payout-report — the sheet finance pays
-- from — carried merchantId, displayName, parcels, netCents, currency, and an
-- operator then had to find that seller's bank details somewhere outside this
-- system entirely: an email, a spreadsheet, a WhatsApp message. The one fact a
-- payment cannot be made without was the one fact the platform did not hold.
--
-- Lives on marketplace_seller rather than a table of its own: a destination is
-- a property of the seller, exactly one per seller, and V8 already keyed that
-- record by merchant_id. A separate table would add a join and a way for a
-- seller to have two.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. The destination.
--
-- Two methods, because those are the two rails a Zimbabwean seller is actually
-- paid on: a mobile-money wallet (an MSISDN) or a bank transfer.
--
-- payout_account_name is on BOTH and is NOT redundant with display_name: the
-- trading name is what shoppers see, while this is the name the destination
-- account is actually held in, which is what finance checks the transfer
-- against and what a bank rejects a payment for not matching. They differ
-- routinely and legitimately — "Rudo Traders" paying into "R. Chikwanha".
-- ---------------------------------------------------------------------------
ALTER TABLE marketplace_seller
    ADD COLUMN payout_method         VARCHAR(20)
        CHECK (payout_method IN ('MOBILE_MONEY', 'BANK')),
    ADD COLUMN payout_account_name   VARCHAR(120),
    -- MOBILE_MONEY: E.164, normalised through the same libphonenumber path as
    -- every other number here, so a payout target cannot be stored in a
    -- spelling the rails will not accept.
    ADD COLUMN payout_msisdn         VARCHAR(20),
    -- BANK. The account number is free text and deliberately unvalidated
    -- beyond a length bound: account-number formats vary per bank and a format
    -- guess that refuses a valid account is worse than no check at all.
    ADD COLUMN payout_bank_name      VARCHAR(120),
    ADD COLUMN payout_account_number VARCHAR(40),
    -- Who last changed it and when. This is NOT bookkeeping: redirecting a
    -- payout is the attack on this feature, so the report shows finance how
    -- recently the destination moved, and the audit row says who moved it.
    ADD COLUMN payout_updated_at     TIMESTAMPTZ,
    ADD COLUMN payout_updated_by     UUID;

-- ---------------------------------------------------------------------------
-- 2. A destination is complete, or it is absent. Never half.
--
-- The dangerous shape is a row that LOOKS payable and is not — a method with
-- no account behind it, which reads as configured on every screen and fails at
-- the moment a transfer is attempted. The CHECK makes that unrepresentable
-- rather than trusting each future write path to remember, the same call V9
-- made with chk_order_delivery_destination.
--
-- It also pins each method to its OWN fields: a BANK row carrying an msisdn is
-- ambiguous about which one a payment should follow.
-- ---------------------------------------------------------------------------
ALTER TABLE marketplace_seller
    ADD CONSTRAINT chk_seller_payout_destination CHECK (
        (payout_method IS NULL
             AND payout_account_name IS NULL
             AND payout_msisdn IS NULL
             AND payout_bank_name IS NULL
             AND payout_account_number IS NULL)
        OR (payout_method = 'MOBILE_MONEY'
             AND payout_account_name IS NOT NULL
             AND payout_msisdn IS NOT NULL
             AND payout_bank_name IS NULL
             AND payout_account_number IS NULL)
        OR (payout_method = 'BANK'
             AND payout_account_name IS NOT NULL
             AND payout_bank_name IS NOT NULL
             AND payout_account_number IS NOT NULL
             AND payout_msisdn IS NULL)
    );

-- ---------------------------------------------------------------------------
-- 3. No backfill, and nothing is blocked by its absence.
--
-- Every existing seller has no destination on file, which is the truth: none
-- was ever collected. They are prompted for one the next time they look at
-- their money.
--
-- Deliberately NOT a gate on the payout run. POST /marketplace/settlements/
-- pay-out RECORDS a transfer the operator has already made on the rails;
-- refusing to record one because this service holds no destination would leave
-- the ledger saying RELEASABLE after the money had left, and the next run
-- would pay those same parcels a second time. A ledger that cannot record a
-- payment that happened is worse than one holding an incomplete address book.
-- The destination is surfaced where it changes an outcome instead: on the
-- payout report, which is read in the moment BEFORE the money moves.
-- ---------------------------------------------------------------------------
