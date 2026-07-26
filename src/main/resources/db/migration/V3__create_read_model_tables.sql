-- Read-model tables for the query side (SPECS §7). Populated by the read-model projector
-- consumer as it consumes events off the queue (SPECS §8) — never written to by command
-- handlers, and never joined against the write-side `promotions` table.

CREATE TABLE promotion_detail (
    id                    UUID PRIMARY KEY,
    application_id        UUID NOT NULL,
    version               VARCHAR(255) NOT NULL,
    from_environment       VARCHAR(20),
    target_environment     VARCHAR(20) NOT NULL,
    status                VARCHAR(20) NOT NULL,
    requested_by_user_id   VARCHAR(255) NOT NULL,
    approved_by_user_id    VARCHAR(255)
);

-- One row per event consumed for a promotion, appended by the projector rather than recomputed
-- on read; backs the `history` array embedded in GET /promotions/:id.
CREATE TABLE promotion_detail_history (
    id             BIGSERIAL PRIMARY KEY,
    promotion_id   UUID NOT NULL REFERENCES promotion_detail (id),
    status         VARCHAR(20) NOT NULL,
    actor_user_id  VARCHAR(255) NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_promotion_detail_history_promotion ON promotion_detail_history (promotion_id, occurred_at);

-- One row per (application, environment); backs GET /applications/:id/status.
CREATE TABLE application_environment_status (
    application_id     UUID NOT NULL,
    environment         VARCHAR(20) NOT NULL,
    current_version     VARCHAR(255),
    status              VARCHAR(20),
    last_promotion_id   UUID,
    PRIMARY KEY (application_id, environment)
);

-- One row per promotion; backs the paged GET /applications/:id/promotions.
CREATE TABLE promotion_history (
    id                   UUID PRIMARY KEY,
    application_id       UUID NOT NULL,
    version              VARCHAR(255) NOT NULL,
    from_environment      VARCHAR(20),
    target_environment    VARCHAR(20) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    requested_at          TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ
);

CREATE INDEX idx_promotion_history_application ON promotion_history (application_id, requested_at DESC);
