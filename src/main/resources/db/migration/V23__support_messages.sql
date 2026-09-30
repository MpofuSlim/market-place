-- Customer support (call centre), part 2: every message support sends a
-- customer, and what became of it.
--
-- One row per attempt, whatever the kind: a message an agent typed (CUSTOM),
-- or a platform message support re-sent (ORDER_CONFIRMATION, PARCEL_UPDATE,
-- COLLECT_CODE). The rows are also the rate limiter's ledger — per agent per
-- hour, per recipient per day — so an attempt counts whether or not it was
-- delivered.
--
-- recipient_msisdn is ALWAYS a number already on the record the agent looked
-- up (the order's payer, gift recipient or delivery recipient; a buyer's own
-- number). No endpoint takes a destination number from an agent.
--
-- body is exactly what was sent — the GSM-safe form when SMS carried it, the
-- original when WhatsApp did — and NULL for a kind that carries a secret
-- (COLLECT_CODE): a code nobody on the console may read is not stored for them
-- to read later either.
CREATE TABLE support_message (
    id                UUID         PRIMARY KEY,
    subject_kind      VARCHAR(20)  NOT NULL,
    subject_id        VARCHAR(80)  NOT NULL,
    recipient_msisdn  VARCHAR(20)  NOT NULL,
    recipient_role    VARCHAR(30)  NOT NULL,
    kind              VARCHAR(30)  NOT NULL,
    channel_requested VARCHAR(20)  NOT NULL,
    delivered_via     VARCHAR(20),
    outcome           VARCHAR(20)  NOT NULL,
    body              TEXT,
    failure_code      VARCHAR(60),
    agent_uuid        VARCHAR(64)  NOT NULL,
    agent_login       VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT chk_support_message_subject CHECK (subject_kind IN ('BUYER', 'ORDER', 'SELLER')),
    CONSTRAINT chk_support_message_channel
        CHECK (channel_requested IN ('SMS', 'WHATSAPP', 'SMS_THEN_WHATSAPP')),
    CONSTRAINT chk_support_message_outcome CHECK (outcome IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_support_message_delivered_via
        CHECK (delivered_via IS NULL OR delivered_via IN ('SMS', 'WHATSAPP')),
    -- A channel is named exactly when the message went out. Both sides are
    -- definite (outcome is NOT NULL; IS NOT NULL is never UNKNOWN), so this
    -- CHECK cannot pass by evaluating to UNKNOWN — the V17 lesson.
    CONSTRAINT chk_support_message_sent_via
        CHECK ((outcome = 'SENT') = (delivered_via IS NOT NULL)),
    CONSTRAINT chk_support_message_completed
        CHECK ((outcome = 'PENDING') = (completed_at IS NULL))
);
CREATE INDEX idx_support_message_recipient ON support_message (recipient_msisdn, created_at DESC);
CREATE INDEX idx_support_message_agent     ON support_message (agent_uuid, created_at DESC);
CREATE INDEX idx_support_message_subject   ON support_message (subject_kind, subject_id, created_at DESC);
CREATE INDEX idx_support_message_created   ON support_message (created_at DESC);

-- A message is written PENDING before the gateway is called (that is what
-- claims its rate-limit slot) and completed ONCE afterwards. The trigger makes
-- that the only change a row can ever see: nothing is deleted, a completed row
-- is final, and who sent what to whom never changes. body may still change
-- while PENDING, because which channel carried it — and so which form of the
-- text was sent — is only known after the send.
CREATE FUNCTION support_message_is_final() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'support_message is append-only';
    END IF;
    IF OLD.outcome <> 'PENDING' THEN
        RAISE EXCEPTION 'support_message % is already %', OLD.id, OLD.outcome;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind
        OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
        OR NEW.recipient_msisdn IS DISTINCT FROM OLD.recipient_msisdn
        OR NEW.recipient_role IS DISTINCT FROM OLD.recipient_role
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.channel_requested IS DISTINCT FROM OLD.channel_requested
        OR NEW.agent_uuid IS DISTINCT FROM OLD.agent_uuid
        OR NEW.agent_login IS DISTINCT FROM OLD.agent_login
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'support_message % may only record its outcome', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_support_message_is_final
    BEFORE UPDATE OR DELETE ON support_message
    FOR EACH ROW EXECUTE FUNCTION support_message_is_final();
