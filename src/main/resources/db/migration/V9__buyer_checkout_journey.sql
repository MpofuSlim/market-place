-- =============================================================================
-- V9: the buyer checkout journey — cart, delivery, checkout totals, fulfilment.
--
-- Until now the buyer surface stopped at both ends of the middle. A shopper had
-- nowhere to put what they wanted to buy (no cart — every client held one in
-- local storage, so it died with the device), an order carried no indication of
-- WHERE the goods should go, and PAID was the last thing that ever happened to
-- an order: the merchant had no queue to work and the buyer had nothing to
-- track. This migration adds the four missing pieces; the endpoints on top of
-- them are additive, so every existing contract is unchanged.
--
-- Conventions (fleet-wide): money in MINOR units (cents, BIGINT); timestamps
-- TIMESTAMPTZ written as UTC Instants; schema is Flyway-owned.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Cart.
--
-- Composite-PK shape (buyer_uuid, listing_id), same as V6 favorites: a cart IS
-- its lines, so a `cart` header row would carry nothing but a second id to keep
-- in step. One row per listing per buyer makes "add the same thing twice" an
-- UPSERT rather than a duplicate, by construction.
--
-- The cart holds NO stock and no price. Quantity is the only thing stored;
-- price, availability and stock are resolved LIVE on every read, because a cart
-- can sit for weeks and a stored price would quietly become a promise the
-- catalogue no longer makes. Stock is reserved at order creation, never here —
-- a cart that held stock would let anyone freeze a merchant's inventory for
-- free.
--
-- ON DELETE CASCADE on listing_id: listings are soft-deleted (ARCHIVED) rather
-- than removed, so this should never fire — it is a backstop that keeps a cart
-- from ever pointing at a row that is genuinely gone.
-- ---------------------------------------------------------------------------
CREATE TABLE cart_item (
    buyer_uuid  UUID        NOT NULL,
    listing_id  UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    added_at    TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (buyer_uuid, listing_id)
);

-- The cart read: one buyer's lines, most recently added first.
CREATE INDEX idx_cart_item_buyer ON cart_item (buyer_uuid, added_at DESC);

-- ---------------------------------------------------------------------------
-- 2. Delivery address book.
--
-- The buyer-side equivalent of "my existing payment methods": a shopper saves
-- an address once and picks it at checkout instead of retyping it on a phone
-- keyboard every time.
--
-- recipient_name / recipient_msisdn are deliberately SEPARATE from the buyer's
-- own identity: ordering something delivered to a relative is the common case,
-- and the person who must be phoned by the courier is whoever is at the door.
-- The msisdn is normalised to E.164 before storage, like market_order.buyer_msisdn.
--
-- No soft-delete column: an order never references this row for its shipping
-- detail (it snapshots the values — see 3 below), so deleting an address can
-- never orphan or rewrite an order already in flight.
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_address (
    id               UUID PRIMARY KEY,
    buyer_uuid       UUID         NOT NULL,
    -- Shopper's own name for the entry ("Home", "Work"). Optional: the address
    -- itself is what identifies it.
    label            VARCHAR(40),
    recipient_name   VARCHAR(120) NOT NULL,
    recipient_msisdn VARCHAR(20)  NOT NULL,
    line1            VARCHAR(160) NOT NULL,
    line2            VARCHAR(160),
    city             VARCHAR(80)  NOT NULL,
    area             VARCHAR(80),
    -- "Opposite the clinic, blue gate." In the markets this cell serves, the
    -- landmark is what actually gets a courier to the door.
    landmark         VARCHAR(160),
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_address_buyer ON delivery_address (buyer_uuid, created_at DESC);

-- At most ONE default per buyer, enforced by the database rather than by
-- remembering to demote the old one — the same partial-unique-index backstop
-- V3 puts under the listing gallery's single primary image. App-side, a default
-- swap runs as ORDERED statements (demote THEN mark) so the index never sees
-- two defaults mid-transaction.
CREATE UNIQUE INDEX uq_address_default_per_buyer ON delivery_address (buyer_uuid)
    WHERE is_default;

-- ---------------------------------------------------------------------------
-- 3. Orders: the money breakdown and the delivery snapshot.
--
-- total_cents was previously the sum of the lines and nothing else. With a
-- delivery fee it stops being derivable from the items, so the split is stored:
-- total_cents = subtotal_cents + delivery_fee_cents, and total_cents REMAINS
-- the single number the payments service collects (its internal view is
-- unchanged and needs no coordinated deploy).
--
-- The address is SNAPSHOT onto the order, not referenced by id — the
-- title_snapshot discipline, one level up. A buyer who edits "Home" after
-- ordering must not silently redirect a parcel that is already on its way, and
-- deleting the book entry must not erase where a delivered order went.
-- delivery_address_id is kept for provenance only (which book entry was
-- chosen); nothing reads through it.
-- ---------------------------------------------------------------------------
ALTER TABLE market_order
    ADD COLUMN subtotal_cents            BIGINT,
    ADD COLUMN delivery_fee_cents        BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN delivery_method           VARCHAR(16),
    ADD COLUMN delivery_address_id       UUID,
    ADD COLUMN delivery_recipient_name   VARCHAR(120),
    ADD COLUMN delivery_recipient_msisdn VARCHAR(20),
    ADD COLUMN delivery_line1            VARCHAR(160),
    ADD COLUMN delivery_line2            VARCHAR(160),
    ADD COLUMN delivery_city             VARCHAR(80),
    ADD COLUMN delivery_area             VARCHAR(80),
    ADD COLUMN delivery_landmark         VARCHAR(160);

-- Pre-V9 rows carried no fee, so their total IS their subtotal.
UPDATE market_order SET subtotal_cents = total_cents WHERE subtotal_cents IS NULL;

-- COLLECTION for pre-V9 rows, deliberately — not DELIVERY. No address was ever
-- captured for them, so nothing was ever going to be delivered; calling them
-- DELIVERY would assert a shipment the platform has no destination for.
UPDATE market_order SET delivery_method = 'COLLECTION' WHERE delivery_method IS NULL;

ALTER TABLE market_order
    ALTER COLUMN subtotal_cents  SET NOT NULL,
    ALTER COLUMN delivery_method SET NOT NULL;

ALTER TABLE market_order
    ADD CONSTRAINT chk_order_subtotal      CHECK (subtotal_cents > 0),
    ADD CONSTRAINT chk_order_delivery_fee  CHECK (delivery_fee_cents >= 0),
    ADD CONSTRAINT chk_order_total_split   CHECK (total_cents = subtotal_cents + delivery_fee_cents),
    ADD CONSTRAINT chk_order_delivery_method
        CHECK (delivery_method IN ('DELIVERY', 'COLLECTION')),
    -- A DELIVERY order with no destination is the exact hole this migration
    -- exists to close; the database refuses to store one rather than trusting
    -- every future write path to remember.
    ADD CONSTRAINT chk_order_delivery_destination CHECK (
        delivery_method <> 'DELIVERY'
        OR (delivery_recipient_name IS NOT NULL
            AND delivery_recipient_msisdn IS NOT NULL
            AND delivery_line1 IS NOT NULL
            AND delivery_city IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 4. Order lines carry their seller.
--
-- An order line named a listing and nothing else, so "which of my orders are
-- mine to pack?" could only be answered by joining every line back to the live
-- listing table. That join is also a lie waiting to happen: a listing can be
-- transferred or archived, and the seller who must be paid and must ship is the
-- one who was selling AT ORDER TIME. Same reasoning as title_snapshot and
-- unit_price_cents beside it.
--
-- Backfill is exhaustive: listings are soft-deleted (ARCHIVED), never removed,
-- so every existing line resolves.
-- ---------------------------------------------------------------------------
ALTER TABLE market_order_item ADD COLUMN merchant_id UUID;

UPDATE market_order_item i
   SET merchant_id = l.merchant_id
  FROM listing l
 WHERE l.id = i.listing_id
   AND i.merchant_id IS NULL;

ALTER TABLE market_order_item ALTER COLUMN merchant_id SET NOT NULL;

CREATE INDEX idx_order_item_merchant ON market_order_item (merchant_id);

-- ---------------------------------------------------------------------------
-- 5. Fulfilment — one parcel per (order, merchant).
--
-- Per MERCHANT, not per order, because a cart can legitimately span sellers and
-- each seller ships their own goods on their own clock. An order-level
-- fulfilment state would either block the fast seller behind the slow one or
-- claim the whole order shipped when half of it had. The buyer's order view
-- rolls the parcels up to the LEAST advanced one, which is the only summary
-- that is never an overstatement.
--
-- Rows are opened when the order is PAID (in the confirming transaction), so an
-- unpaid order has none — there is nothing to pack until the money has moved.
--
-- Deliberately NOT extra values on market_order.status: PENDING_PAYMENT is what
-- payment-service reads to decide an order is payable, PAID is what the
-- verified-purchase review gate queries, and both are terminal-or-not in ways
-- that fulfilment must not disturb. Payment state and fulfilment state are
-- different questions about the same order and are stored as such.
-- ---------------------------------------------------------------------------
CREATE TABLE order_fulfilment (
    id            UUID PRIMARY KEY,
    order_id      UUID        NOT NULL REFERENCES market_order (id),
    merchant_id   UUID        NOT NULL,
    status        VARCHAR(24) NOT NULL CHECK (status IN ('PREPARING', 'DISPATCHED', 'DELIVERED')),
    -- Courier + waybill, or "ready at the Avondale counter". Sanitized free
    -- text, shown to the buyer.
    dispatch_note VARCHAR(255),
    dispatched_at TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ,
    -- Who closed it: BUYER (confirmed receipt) or MERCHANT (marked handed
    -- over). Worth keeping distinct — a parcel the buyer confirmed is evidence
    -- of delivery in a way a seller's own say-so is not.
    delivered_by  VARCHAR(16) CHECK (delivered_by IN ('BUYER', 'MERCHANT')),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0
);

-- One parcel per seller per order, by construction — so opening parcels for an
-- order is safely re-runnable (a replayed payment confirm cannot double them).
CREATE UNIQUE INDEX uq_fulfilment_order_merchant ON order_fulfilment (order_id, merchant_id);

-- The seller's queue: my parcels in one state, oldest first (FIFO — the oldest
-- order never starves), mirroring idx_report_status_created.
CREATE INDEX idx_fulfilment_merchant_status ON order_fulfilment (merchant_id, status, created_at);
CREATE INDEX idx_fulfilment_order           ON order_fulfilment (order_id);

-- Open a parcel for every order that is ALREADY paid.
--
-- PREPARING, and deliberately not DELIVERED: some of these were very likely
-- handed over offline, but the platform has no record of that, and marking them
-- delivered would be the platform asserting something it never observed (the
-- same call V8 made backfilling sellers as PENDING rather than APPROVED). A
-- seller closes them in one pass from their queue; the buyer sees the true
-- state meanwhile.
INSERT INTO order_fulfilment (id, order_id, merchant_id, status, created_at, updated_at)
SELECT gen_random_uuid(), s.order_id, s.merchant_id, 'PREPARING', s.opened_at, s.opened_at
  FROM (SELECT DISTINCT o.id                                AS order_id,
                        i.merchant_id                       AS merchant_id,
                        COALESCE(o.paid_at, o.updated_at)   AS opened_at
          FROM market_order o
          JOIN market_order_item i ON i.order_id = o.id
         WHERE o.status = 'PAID') s
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. The journal learns which lifecycle a row belongs to.
--
-- market_order_event now carries both payment transitions and fulfilment ones.
-- The two vocabularies do not overlap today, so a reader could infer it — but
-- inferring the meaning of a column from its values is exactly how a journal
-- becomes unreadable the first time a status name is reused. PAYMENT for every
-- existing row, which is what they all are.
-- ---------------------------------------------------------------------------
ALTER TABLE market_order_event
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PAYMENT'
        CHECK (kind IN ('PAYMENT', 'FULFILMENT'));
