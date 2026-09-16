-- =============================================================================
-- V11: gifting — an order can name WHO it is for, and a collection can be
-- proven.
--
-- Two halves of the same journey, and neither works alone.
--
-- The marketplace could only ever be bought from for YOURSELF. A diaspora buyer
-- paying for their mother's groceries had nowhere to say whose groceries they
-- were: the order carried the PAYER's name and phone, the recipient was never
-- told anything, and at the counter the seller had no way to know who was
-- entitled to collect. The delivery address's recipient fields (V9) answer
-- "who does the courier ring", which is a different question — they exist only
-- on DELIVERY orders and say nothing about a gift.
--
-- So: the order names a recipient, and a COLLECTION parcel can carry a
-- handover code that the person collecting presents and the seller verifies.
--
-- Conventions: money in MINOR units; timestamps TIMESTAMPTZ (UTC Instants);
-- schema Flyway-owned.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. The order can be FOR someone.
--
-- All three nullable: an order bought for oneself has no recipient, which is
-- most of them, and a NULL says that plainly where a copy of the buyer's own
-- details would assert a gift that was never sent.
--
-- The recipient is NOT an account and never becomes one. They are a name the
-- goods are handed to and a number we can message — they cannot sign in, read
-- the order, or act on it. Making them a party to the order would mean deciding
-- what a stranger may see of someone else's purchase, and nothing here needs
-- that.
--
-- recipient_msisdn is E.164 (normalised by the same Msisdns as every other
-- number here) and is PII with the payer's posture: never logged in full,
-- never on a public surface, and shown to the seller only as the NAME.
-- ---------------------------------------------------------------------------
ALTER TABLE market_order
    ADD COLUMN recipient_name    VARCHAR(120),
    ADD COLUMN recipient_msisdn  VARCHAR(20),
    -- The note that turns a delivery into a gift ("Happy birthday Gogo").
    -- Sanitized free text, shown to the recipient; capped short because it
    -- rides an SMS.
    ADD COLUMN gift_message      VARCHAR(200);

-- ---------------------------------------------------------------------------
-- 2. A COLLECTION parcel can carry a handover code.
--
-- The code is a CREDENTIAL, so only its SHA-256 lives here — the plaintext
-- exists in the mint response and in the SMS, and nowhere else, ever. It is
-- minted from 60 bits of SecureRandom over a 32-character alphabet, which is
-- why an unkeyed hash is the right tool and not the fleet's HMAC rule for
-- low-entropy secrets (an OTP's million-value space is a dictionary; this one
-- is not). No new boot-required secret, and therefore no cell provisioning
-- change.
--
-- collect_code_attempts is the online-guessing budget, and it is NOT decorative:
-- a merchant is the only party who can submit a candidate, and they submit
-- against their OWN parcel, so the cap is what stops a seller from guessing
-- their way to the instant payout that a genuine handover earns. It survives
-- the refusal because it is incremented in its own transaction — a counter
-- rolled back by the exception it is counting would count nothing (the
-- failed-PIN lesson from the middleware, imported deliberately).
--
-- No backfill: existing parcels get no code. A code minted for a parcel nobody
-- was ever told about is a credential no one holds — pre-V11 collections close
-- the way they always have, by the buyer confirming or the seller self-closing.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment
    ADD COLUMN collect_code_hash        VARCHAR(64),
    ADD COLUMN collect_code_issued_at   TIMESTAMPTZ,
    ADD COLUMN collect_code_redeemed_at TIMESTAMPTZ,
    ADD COLUMN collect_code_attempts    INTEGER NOT NULL DEFAULT 0
        CHECK (collect_code_attempts >= 0);

-- ---------------------------------------------------------------------------
-- 3. A third kind of evidence that a parcel arrived.
--
-- BUYER (confirmed in the app) and MERCHANT (the seller's own say-so) were the
-- only two. A redeemed collection code is neither: it says a person holding a
-- secret that only the buyer and the recipient were ever given stood at the
-- counter. That is why it releases the seller's money immediately, exactly like
-- the buyer's own confirmation, while the seller's self-close still waits out
-- the grace window.
-- ---------------------------------------------------------------------------
ALTER TABLE order_fulfilment
    DROP CONSTRAINT order_fulfilment_delivered_by_check;
ALTER TABLE order_fulfilment
    ADD CONSTRAINT order_fulfilment_delivered_by_check
        CHECK (delivered_by IN ('BUYER', 'MERCHANT', 'RECIPIENT'));
