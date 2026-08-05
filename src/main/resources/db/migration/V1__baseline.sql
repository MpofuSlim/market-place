-- =============================================================================
-- V1: marketplace-service baseline schema.
--
-- Conventions (fleet-wide):
--   * All money in MINOR UNITS (cents, BIGINT) — never decimals.
--   * All timestamps TIMESTAMPTZ, written as UTC Instants by the app.
--   * Schema is Flyway-owned; Hibernate runs ddl-auto: validate.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Catalog: merchant product listings.
-- merchant_id / shop_id come from the fleet JWT's merchantId / shopId claims
-- (user-service scoping) — never from a request body.
-- ---------------------------------------------------------------------------
CREATE TABLE listing (
    id             UUID PRIMARY KEY,
    merchant_id    UUID         NOT NULL,
    shop_id        UUID,
    title          VARCHAR(160) NOT NULL,
    description    VARCHAR(4000),
    category       VARCHAR(64),
    price_cents    BIGINT       NOT NULL CHECK (price_cents > 0),
    currency       VARCHAR(3)   NOT NULL,
    stock_qty      INTEGER      NOT NULL CHECK (stock_qty >= 0),
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_listing_merchant        ON listing (merchant_id);
CREATE INDEX idx_listing_status_category ON listing (status, category);
CREATE INDEX idx_listing_status_created  ON listing (status, created_at DESC);

-- ---------------------------------------------------------------------------
-- Orders. order_ref is the payments-facing opaque reference (MKT-<12 hex>) —
-- the shape the platform payments service will consume, mirroring how
-- booking-service exposes bookings to payment-service today.
-- stock_released is the double-release guard: expiry/cancel restocks exactly
-- once even if sweeps overlap a concurrent cancel.
-- ---------------------------------------------------------------------------
CREATE TABLE market_order (
    id               UUID PRIMARY KEY,
    order_ref        VARCHAR(40)  NOT NULL,
    buyer_uuid       UUID         NOT NULL,
    buyer_msisdn     VARCHAR(20)  NOT NULL,
    status           VARCHAR(24)  NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED')),
    total_cents      BIGINT       NOT NULL CHECK (total_cents > 0),
    currency         VARCHAR(3)   NOT NULL,
    payment_ref      VARCHAR(64),
    paid_at          TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ  NOT NULL,
    stock_released   BOOLEAN      NOT NULL DEFAULT FALSE,
    idempotency_key  VARCHAR(128),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_order_ref             ON market_order (order_ref);
-- DB backstop under the idempotency claim-row: two rows can never share a key.
CREATE UNIQUE INDEX uq_order_idempotency_key ON market_order (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_order_buyer          ON market_order (buyer_uuid, created_at DESC);
CREATE INDEX idx_order_status_expires ON market_order (status, expires_at);

CREATE TABLE market_order_item (
    id                UUID PRIMARY KEY,
    order_id          UUID         NOT NULL REFERENCES market_order (id),
    listing_id        UUID         NOT NULL,
    title_snapshot    VARCHAR(160) NOT NULL,
    unit_price_cents  BIGINT       NOT NULL CHECK (unit_price_cents > 0),
    quantity          INTEGER      NOT NULL CHECK (quantity > 0),
    line_total_cents  BIGINT       NOT NULL CHECK (line_total_cents > 0)
);

CREATE INDEX idx_order_item_order   ON market_order_item (order_id);
CREATE INDEX idx_order_item_listing ON market_order_item (listing_id);

-- Append-only order transition journal, written in the SAME transaction as the
-- status change (payment-service payment_event pattern).
CREATE TABLE market_order_event (
    id           BIGSERIAL PRIMARY KEY,
    order_id     UUID         NOT NULL,
    from_status  VARCHAR(24),
    to_status    VARCHAR(24)  NOT NULL,
    detail       VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_order_event_order ON market_order_event (order_id);

-- ---------------------------------------------------------------------------
-- Idempotency claim rows (InnBucksMiddleware2.0 design): the key is CLAIMED
-- with INSERT .. ON CONFLICT DO NOTHING (status 0 sentinel) BEFORE any work
-- runs, so concurrent same-key requests can never double-execute. Replays
-- return the ORIGINAL stored status + body.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    key_hash      VARCHAR(64)  PRIMARY KEY,
    request_hash  VARCHAR(64)  NOT NULL,
    status        INTEGER      NOT NULL,
    response_body TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- ---------------------------------------------------------------------------
-- Tamper-evident audit log (user-service V29/V32, payment-service V9/V10
-- design). row_hmac seals each row's content; chain_hmac binds it to its
-- predecessor so deletion/reordering breaks the chain. Writes serialise on
-- the single-row audit_chain_head via SELECT .. FOR UPDATE in a REQUIRES_NEW
-- transaction.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    event_type  VARCHAR(64)  NOT NULL,
    actor_uuid  VARCHAR(64),
    target_id   VARCHAR(64),
    metadata    TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    row_hmac    VARCHAR(64)  NOT NULL,
    chain_hmac  VARCHAR(64)
);

CREATE INDEX idx_audit_events_created ON audit_events (created_at);
CREATE INDEX idx_audit_events_type    ON audit_events (event_type);

CREATE TABLE audit_chain_head (
    id              SMALLINT PRIMARY KEY CHECK (id = 1),
    last_chain_hmac VARCHAR(64),
    last_event_id   BIGINT
);

INSERT INTO audit_chain_head (id, last_chain_hmac, last_event_id)
VALUES (1, NULL, NULL);

-- ---------------------------------------------------------------------------
-- ShedLock: leader election for @Scheduled jobs (expiry sweep, audit verify)
-- so a second replica never double-fires them.
-- ---------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
