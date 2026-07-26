CREATE TABLE outbox_events (
    id               UUID PRIMARY KEY,
    event_type       VARCHAR(50) NOT NULL,
    promotion_id     UUID NOT NULL,
    application_id   UUID NOT NULL,
    acting_user      VARCHAR(255) NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    payload          JSONB NOT NULL,
    published_at     TIMESTAMPTZ
);

-- Scopes OutboxRelay's poll for events not yet delivered to the broker.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;
