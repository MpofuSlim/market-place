-- =============================================================================
-- V17: close the NULL hole in chk_seller_payout_destination (V13).
--
-- V13's CHECK exists to make a payout destination "complete or absent, never
-- half". It did that for every row that NAMES a method, but not for one that
-- does not. SQL comparisons are three-valued: with payout_method NULL,
-- `payout_method = 'MOBILE_MONEY'` is UNKNOWN, not FALSE. Take a row with no
-- method but with an account name and an msisdn:
--
--   branch 1 (everything NULL)           -> FALSE  (the details are set)
--   branch 2 (payout_method = 'MOBILE…') -> UNKNOWN AND TRUE…  = UNKNOWN
--   branch 3 (payout_method = 'BANK' …)  -> FALSE  (msisdn IS NULL is false)
--   FALSE OR UNKNOWN OR FALSE            -> UNKNOWN
--
-- and a CHECK whose expression is UNKNOWN PASSES. So account details with no
-- method were storable — exactly the half-destination the constraint was
-- written to refuse. V16's chk_fulfilment_unfulfilled_by had the same shape
-- and needed the same explicit IS NOT NULL.
--
-- Impact was low, and this is why: no write path produces the shape
-- (SellerService.setPayoutDestination always writes the method with the
-- details, and nothing ever clears the method), and every read path —
-- hasPayoutDestination(), the payout-destination response, the payout report
-- CSV — gates on payout_method, so such a row reads as "not configured" and
-- its details are never shown. Only a hand-written UPDATE could create one.
--
-- Conventions: schema Flyway-owned; Flyway runs this file in ONE transaction
-- on Postgres, so there is no instant at which the table has no constraint.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Clear any row the new constraint would refuse.
--
-- A detail with no method was never payable and never displayed (see above),
-- so clearing it loses nothing any screen or report ever showed. Keeping it
-- would keep an account number or a phone number for no purpose, and the new
-- CHECK could not be added over it. Expected to touch ZERO rows on every
-- cell; the WARNING below says so in the Flyway log if it ever does not.
--
-- payout_updated_at / payout_updated_by are deliberately LEFT: the CHECK does
-- not govern them, and they are the only trace on the row of who last touched
-- it, which is exactly what anyone asking how the stray values got there needs.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    cleared INTEGER;
BEGIN
    UPDATE marketplace_seller
       SET payout_account_name   = NULL,
           payout_msisdn         = NULL,
           payout_bank_name      = NULL,
           payout_account_number = NULL
     WHERE payout_method IS NULL
       AND (payout_account_name   IS NOT NULL
            OR payout_msisdn         IS NOT NULL
            OR payout_bank_name      IS NOT NULL
            OR payout_account_number IS NOT NULL);
    GET DIAGNOSTICS cleared = ROW_COUNT;
    IF cleared > 0 THEN
        RAISE WARNING 'V17 cleared payout details with no payout_method on % marketplace_seller row(s) - they were never payable or shown', cleared;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 2. The same three shapes, with every branch now DEFINITE.
--
-- `payout_method IS NOT NULL` in branches 2 and 3 turns the method comparison
-- from UNKNOWN into FALSE when there is no method, so the whole expression is
-- always TRUE or FALSE and a method-less row with details is refused. Branch 1
-- needed nothing: IS NULL is never UNKNOWN. The legal shapes are unchanged —
-- every row V13 accepted with a method is still accepted.
-- ---------------------------------------------------------------------------
ALTER TABLE marketplace_seller
    DROP CONSTRAINT chk_seller_payout_destination;

ALTER TABLE marketplace_seller
    ADD CONSTRAINT chk_seller_payout_destination CHECK (
        (payout_method IS NULL
             AND payout_account_name IS NULL
             AND payout_msisdn IS NULL
             AND payout_bank_name IS NULL
             AND payout_account_number IS NULL)
        OR (payout_method IS NOT NULL
             AND payout_method = 'MOBILE_MONEY'
             AND payout_account_name IS NOT NULL
             AND payout_msisdn IS NOT NULL
             AND payout_bank_name IS NULL
             AND payout_account_number IS NULL)
        OR (payout_method IS NOT NULL
             AND payout_method = 'BANK'
             AND payout_account_name IS NOT NULL
             AND payout_bank_name IS NOT NULL
             AND payout_account_number IS NOT NULL
             AND payout_msisdn IS NULL)
    );
