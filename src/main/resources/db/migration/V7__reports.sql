-- =============================================================================
-- V7: listing reports + SUPER_ADMIN moderation queue.
--
-- Any authenticated user may report a listing (never anonymous — spam
-- control); the partial unique index allows ONE OPEN report per reporter per
-- listing while keeping the full history of resolved/dismissed ones.
-- (status, created_at) serves the moderation queue's default OPEN-oldest-first
-- read.
-- =============================================================================
CREATE TABLE listing_report (
    id              UUID PRIMARY KEY,
    listing_id      UUID         NOT NULL REFERENCES listing (id),
    reporter_uuid   UUID         NOT NULL,
    reason          VARCHAR(30)  NOT NULL CHECK (reason IN
                        ('PROHIBITED_ITEM', 'COUNTERFEIT', 'MISLEADING',
                         'OFFENSIVE', 'SCAM', 'OTHER')),
    detail          VARCHAR(500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN
                        ('OPEN', 'RESOLVED', 'DISMISSED')),
    resolved_by     UUID,
    resolution_note VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_report_open_per_reporter
    ON listing_report (reporter_uuid, listing_id) WHERE status = 'OPEN';
CREATE INDEX idx_report_status_created ON listing_report (status, created_at);
