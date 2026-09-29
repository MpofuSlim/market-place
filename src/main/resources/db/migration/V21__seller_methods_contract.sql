-- =============================================================================
-- V21: every parcel carries its OWN delivery method, and nothing can say
-- otherwise. The CONTRACT half of V20's expand/contract.
--
-- DEPLOY ORDER - READ THIS FIRST.
-- V20 was EXPAND only: it added market_order_seller and a NULLABLE
-- order_fulfilment.delivery_method, because the V19 image was still running
-- during that rollout and its openIfAbsent INSERT does not name the column.
-- This migration makes the column NOT NULL and ties it to the seller row with
-- a foreign key. So:
--
--   * Run it ONLY once the V20 (PR A) image is 100% rolled out - no V19
--     replica left anywhere in the cell. A V19 replica confirming a payment
--     after this runs fails the parcel INSERT on NOT NULL, inside the confirm
--     transaction, after the buyer's money has already been collected.
--   * Once it has run, the PR A image is the OLDEST image that can run. A
--     rollback to V19 is no longer possible: roll forward instead.
--   * The PR A image keeps working on this schema: it writes a
--     market_order_seller row for every seller at order creation, and copies
--     that row's method onto the parcel at PAID (V21MigrationIT replays its
--     statements).
--
-- WHAT IT DOES (one Flyway transaction on Postgres, so the table is never
-- without its constraints):
--   1. A seller row for every (order, seller) that has items but no row - the
--      orders the V19 image created during the V20 rollout. Every such order is
--      uniform, so the order's own method is exact for each of its sellers.
--   2. Guards: refuses to run if backfilling would put a delivery fee on a
--      COLLECTION parcel (money is never rewritten by a migration).
--   3. The parcel's method for every parcel opened without one: its seller
--      row's, else the order's (the two agree on every such order).
--   4. A seller row for any parcel that still lacks one (defence in depth - a
--      parcel is opened from its order's items, so this should insert nothing).
--   5. Guards: refuses to run if a parcel's method disagrees with its seller
--      row (the foreign key below would refuse it anyway, less clearly).
--   6. Clears two meaningless stray values, V17-style, with a WARNING naming
--      the count: a collection code on a DELIVERY parcel (minting is refused
--      for a delivery, so it can never be used) and a courier position on a
--      COLLECTION parcel (latest position only; nothing reads it). Expected to
--      touch ZERO rows on every cell.
--   7. NOT NULL, the (order, seller, method) key, the parcel -> seller-row
--      foreign key, and the two CHECKs step 6 makes addable.
--
-- PRE-FLIGHT (run on the cell BEFORE deploying; each must return 0 - a hit on
-- (a) or (b) makes this migration refuse to run, a hit on (c) or (d) is cleared
-- by it with a WARNING, a hit on (e) is repaired by step 4):
--
--   -- (a) a parcel the backfill would make a COLLECTION carrying a fee
--   SELECT count(*) FROM order_fulfilment f JOIN market_order o ON o.id = f.order_id
--    WHERE f.delivery_method IS NULL AND f.delivery_fee_cents > 0
--      AND COALESCE((SELECT s.delivery_method FROM market_order_seller s
--                     WHERE s.order_id = f.order_id AND s.merchant_id = f.merchant_id),
--                   o.delivery_method) = 'COLLECTION';
--   -- (b) a parcel whose method disagrees with its seller row
--   SELECT count(*) FROM order_fulfilment f JOIN market_order_seller s
--       ON s.order_id = f.order_id AND s.merchant_id = f.merchant_id
--    WHERE f.delivery_method IS NOT NULL AND f.delivery_method <> s.delivery_method;
--   -- (c) a collection code on a DELIVERY parcel
--   SELECT count(*) FROM order_fulfilment f JOIN market_order o ON o.id = f.order_id
--    WHERE COALESCE(f.delivery_method, o.delivery_method) = 'DELIVERY'
--      AND f.collect_code_hash IS NOT NULL;
--   -- (d) a courier position on a COLLECTION parcel
--   SELECT count(*) FROM order_fulfilment f JOIN market_order o ON o.id = f.order_id
--    WHERE COALESCE(f.delivery_method, o.delivery_method) = 'COLLECTION'
--      AND (f.last_location_at IS NOT NULL OR f.last_latitude IS NOT NULL
--           OR f.last_longitude IS NOT NULL OR f.last_accuracy_m IS NOT NULL
--           OR f.last_location_by IS NOT NULL);
--   -- (e) a parcel with no item of its seller on its order
--   SELECT count(*) FROM order_fulfilment f
--    WHERE NOT EXISTS (SELECT 1 FROM market_order_item i
--                       WHERE i.order_id = f.order_id AND i.merchant_id = f.merchant_id);
--
-- Every CHECK here spells out its NULL case: a CHECK that evaluates to UNKNOWN
-- passes (the V17 / V16 lesson). V20's two parcel CHECKs keep their
-- `delivery_method IS NULL OR` branch; with NOT NULL it is simply unreachable.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Seller rows for the orders the V19 image created during the V20 rollout.
--    Every order, whatever its status: the order view reads them before there
--    are parcels, and a PENDING_PAYMENT order can still be paid.
-- ---------------------------------------------------------------------------
INSERT INTO market_order_seller (order_id, merchant_id, delivery_method)
SELECT DISTINCT i.order_id, i.merchant_id, o.delivery_method
  FROM market_order_item i
  JOIN market_order o ON o.id = i.order_id
 WHERE NOT EXISTS (SELECT 1 FROM market_order_seller s
                    WHERE s.order_id = i.order_id AND s.merchant_id = i.merchant_id)
ON CONFLICT (order_id, merchant_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Guard V20's chk_fulfilment_collection_no_fee BEFORE backfilling into it.
--    A parcel that would become a COLLECTION while carrying a delivery fee is
--    a disagreement about MONEY (the seller's settlement includes that fee).
--    A migration must never decide which side is right, so it stops here and
--    names the rows for an operator.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    conflicting INTEGER;
BEGIN
    SELECT count(*) INTO conflicting
      FROM order_fulfilment f
      JOIN market_order o ON o.id = f.order_id
     WHERE f.delivery_method IS NULL
       AND f.delivery_fee_cents > 0
       AND COALESCE((SELECT s.delivery_method FROM market_order_seller s
                      WHERE s.order_id = f.order_id AND s.merchant_id = f.merchant_id),
                    o.delivery_method) = 'COLLECTION';
    IF conflicting > 0 THEN
        RAISE EXCEPTION 'V21 refused: % order_fulfilment row(s) with no delivery_method would become COLLECTION parcels carrying a delivery fee. The fee is money in the seller''s settlement and a migration will not rewrite it. Run pre-flight query (a) in V21__seller_methods_contract.sql to list them, decide each with finance, then redeploy.', conflicting;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 3. The parcel's own method, for every parcel opened without one (before V20,
--    or by a V19 replica during the rollout): its seller row's, falling back
--    to the order's. Two scalar subqueries rather than a join, so a parcel
--    with no seller row still gets the order's method instead of being skipped.
-- ---------------------------------------------------------------------------
UPDATE order_fulfilment f
   SET delivery_method = COALESCE(
           (SELECT s.delivery_method FROM market_order_seller s
             WHERE s.order_id = f.order_id AND s.merchant_id = f.merchant_id),
           (SELECT o.delivery_method FROM market_order o WHERE o.id = f.order_id))
 WHERE f.delivery_method IS NULL;

-- ---------------------------------------------------------------------------
-- 4. Defence in depth: a seller row for any parcel still without one, so the
--    foreign key below cannot fail on a parcel whose seller has no item on the
--    order (none should exist - parcels are opened from the items).
-- ---------------------------------------------------------------------------
INSERT INTO market_order_seller (order_id, merchant_id, delivery_method)
SELECT f.order_id, f.merchant_id, f.delivery_method
  FROM order_fulfilment f
 WHERE f.delivery_method IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM market_order_seller s
                    WHERE s.order_id = f.order_id AND s.merchant_id = f.merchant_id)
ON CONFLICT (order_id, merchant_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Guard the foreign key: a parcel whose method differs from its seller row
--    is a real disagreement about how the goods reach the buyer. Refuse, name
--    it, and leave the decision to a person.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    mismatched INTEGER;
    unset      INTEGER;
BEGIN
    SELECT count(*) INTO unset FROM order_fulfilment WHERE delivery_method IS NULL;
    IF unset > 0 THEN
        RAISE EXCEPTION 'V21 refused: % order_fulfilment row(s) still have no delivery_method after the backfill (their order could not be read). Investigate before redeploying.', unset;
    END IF;
    SELECT count(*) INTO mismatched
      FROM order_fulfilment f
      JOIN market_order_seller s ON s.order_id = f.order_id AND s.merchant_id = f.merchant_id
     WHERE f.delivery_method <> s.delivery_method;
    IF mismatched > 0 THEN
        RAISE EXCEPTION 'V21 refused: % order_fulfilment row(s) carry a delivery_method different from their market_order_seller row. Run pre-flight query (b) in V21__seller_methods_contract.sql to list them and decide each before redeploying.', mismatched;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 6. Clear values that mean nothing on their parcel, so the two CHECKs below
--    can be added. Expected to touch ZERO rows; the WARNING says so in the
--    Flyway log if it ever does not (printed before commit - trust it only
--    beside V21's success row in flyway_schema_history).
--
--    * A collection code on a DELIVERY parcel can never be used: minting is
--      refused for a delivery, and so is presenting one. Its issue stamp goes
--      with it, or the buyer's parcel would still say a code was minted. The
--      redemption stamp and the wrong-guess counter are left: neither is
--      governed by the CHECK, and a redemption stamp would be the only trace
--      of whatever produced the row.
--    * A courier position on a COLLECTION parcel: the position is the latest
--      only, nothing ever shows it on a collection, and all five columns go
--      together (chk_fulfilment_location_complete).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    codes     INTEGER;
    positions INTEGER;
BEGIN
    UPDATE order_fulfilment
       SET collect_code_hash      = NULL,
           collect_code_issued_at = NULL
     WHERE delivery_method = 'DELIVERY'
       AND collect_code_hash IS NOT NULL;
    GET DIAGNOSTICS codes = ROW_COUNT;
    IF codes > 0 THEN
        RAISE WARNING 'V21 cleared a collection code on % DELIVERY parcel(s) - a delivery can never be collected with one', codes;
    END IF;

    UPDATE order_fulfilment
       SET last_latitude    = NULL,
           last_longitude   = NULL,
           last_accuracy_m  = NULL,
           last_location_at = NULL,
           last_location_by = NULL
     WHERE delivery_method = 'COLLECTION'
       AND (last_location_at IS NOT NULL OR last_latitude IS NOT NULL
            OR last_longitude IS NOT NULL OR last_accuracy_m IS NOT NULL
            OR last_location_by IS NOT NULL);
    GET DIAGNOSTICS positions = ROW_COUNT;
    IF positions > 0 THEN
        RAISE WARNING 'V21 cleared a courier position on % COLLECTION parcel(s) - a collection is never tracked', positions;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 7. The contract.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment ALTER COLUMN delivery_method SET NOT NULL;

-- The foreign key's target. (order_id, merchant_id) is already the primary
-- key, so this adds no new rule about the seller rows themselves.
ALTER TABLE market_order_seller
    ADD CONSTRAINT uq_order_seller_method UNIQUE (order_id, merchant_id, delivery_method);

ALTER TABLE order_fulfilment
    -- A parcel travels the way its seller's row on the order says, and no
    -- other way. The row is written at order creation and never changed, so a
    -- parcel can never be opened, or later read, as a different method.
    ADD CONSTRAINT fk_fulfilment_seller_method
        FOREIGN KEY (order_id, merchant_id, delivery_method)
        REFERENCES market_order_seller (order_id, merchant_id, delivery_method),
    -- A collection code only exists where somebody collects.
    ADD CONSTRAINT chk_fulfilment_code_on_collection CHECK (
        collect_code_hash IS NULL
        OR (delivery_method IS NOT NULL AND delivery_method = 'COLLECTION')),
    -- A courier position only exists where there is a courier.
    ADD CONSTRAINT chk_fulfilment_location_on_delivery CHECK (
        (last_latitude IS NULL AND last_longitude IS NULL AND last_accuracy_m IS NULL
             AND last_location_at IS NULL AND last_location_by IS NULL)
        OR (delivery_method IS NOT NULL AND delivery_method = 'DELIVERY'));
