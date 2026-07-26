-- Audit log table (SPECS §8.1): the system of record for "what happened when," populated only by
-- the audit-log consumer as it consumes events off the queue — never written to by command
-- handlers directly, so this table also proves the queue path works end to end.
CREATE TABLE audit_log (
    id               UUID PRIMARY KEY,
    event_type       VARCHAR(50) NOT NULL,
    promotion_id     UUID NOT NULL,
    application_id   UUID NOT NULL,
    acting_user      VARCHAR(255) NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    payload          JSONB NOT NULL
);

CREATE INDEX idx_audit_log_promotion ON audit_log (promotion_id, occurred_at);
