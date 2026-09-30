-- Customer support (call centre), part 1: the activity log, internal notes, and
-- the phone indexes a support search by number needs.
--
-- Agents reach this service through /marketplace/support/**, gated on the
-- support permissions user-service V45 mints into the token's perms claim
-- (marketplace-support:read|manage|supervise). Nothing here is customer-facing.

-- Who looked at or did what, when. Every support search and every view of a
-- buyer, order or seller writes a row, as does every support action: it is the
-- supervisor's oversight feed and the answer to "who opened this customer's
-- record". detail is a small JSON of ids and enums — never free text and never
-- a raw phone number (a phone search stores the number masked).
CREATE TABLE support_activity (
    id           UUID         PRIMARY KEY,
    agent_uuid   VARCHAR(64)  NOT NULL,
    agent_login  VARCHAR(255),
    action       VARCHAR(40)  NOT NULL,
    subject_kind VARCHAR(20),
    subject_id   VARCHAR(80),
    detail       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_support_activity_agent   ON support_activity (agent_uuid, created_at DESC);
CREATE INDEX idx_support_activity_subject ON support_activity (subject_kind, subject_id, created_at DESC);
CREATE INDEX idx_support_activity_created ON support_activity (created_at DESC);

-- Internal notes an agent leaves on a buyer, an order or a seller, for the next
-- agent who picks the case up. Append-only: a support log that can be edited
-- afterwards proves nothing about what was known when.
CREATE TABLE support_note (
    id           UUID          PRIMARY KEY,
    subject_kind VARCHAR(20)   NOT NULL,
    subject_id   VARCHAR(80)   NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    agent_uuid   VARCHAR(64)   NOT NULL,
    agent_login  VARCHAR(255),
    created_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_support_note_subject CHECK (subject_kind IN ('BUYER', 'ORDER', 'SELLER')),
    CONSTRAINT chk_support_note_body CHECK (length(btrim(body)) > 0)
);
CREATE INDEX idx_support_note_subject ON support_note (subject_kind, subject_id, created_at DESC);

-- Append-only is enforced HERE, not just by the absence of an endpoint: the
-- application has no update or delete path, and this makes one impossible to
-- add by accident. TRUNCATE (a statement, not a row operation) is unaffected.
CREATE FUNCTION support_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_support_activity_append_only
    BEFORE UPDATE OR DELETE ON support_activity
    FOR EACH ROW EXECUTE FUNCTION support_log_is_append_only();
CREATE TRIGGER trg_support_note_append_only
    BEFORE UPDATE OR DELETE ON support_note
    FOR EACH ROW EXECUTE FUNCTION support_log_is_append_only();

-- A support search by phone matches the payer, a gift recipient and a delivery
-- recipient. None of the three was indexed: every lookup would scan every order.
CREATE INDEX idx_order_buyer_msisdn ON market_order (buyer_msisdn);
CREATE INDEX idx_order_recipient_msisdn ON market_order (recipient_msisdn)
    WHERE recipient_msisdn IS NOT NULL;
CREATE INDEX idx_order_delivery_recipient_msisdn ON market_order (delivery_recipient_msisdn)
    WHERE delivery_recipient_msisdn IS NOT NULL;
