-- Persists AI-drafted release notes for an approved promotion (SPECS §9, optional). One row per
-- promotion, keyed by promotion_id itself (a promotion is approved at most once), populated by the
-- release-notes agent consumer once its tool-calling loop finishes. Upserted on the same key so
-- redelivery of the same PromotionApproved event under the outbox's at-least-once guarantee
-- (SPECS §5.1) never produces conflicting rows.
CREATE TABLE release_notes (
    promotion_id    UUID PRIMARY KEY,
    application_id  UUID NOT NULL,
    version         VARCHAR(255) NOT NULL,
    draft_text      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
