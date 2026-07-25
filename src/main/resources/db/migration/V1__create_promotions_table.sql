CREATE TABLE promotions (
    id                   UUID PRIMARY KEY,
    application_id       UUID NOT NULL,
    version              VARCHAR(255) NOT NULL,
    from_environment      VARCHAR(20),
    target_environment    VARCHAR(20) NOT NULL,
    requested_by_user_id  VARCHAR(255) NOT NULL,
    requested_by_role     VARCHAR(20) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    approved_by_user_id   VARCHAR(255),
    approved_by_role      VARCHAR(20)
);

-- Scopes PromotionRepositoryPort#findActivePromotionsForTarget (invariant #2).
CREATE INDEX idx_promotions_application_target ON promotions (application_id, target_environment);

-- Scopes PromotionRepositoryPort#findLastCompletedEnvironment (invariant #1).
CREATE INDEX idx_promotions_application_version ON promotions (application_id, version);
