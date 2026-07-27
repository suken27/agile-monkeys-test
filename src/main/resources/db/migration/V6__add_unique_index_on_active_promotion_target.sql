-- Backstops invariant #2 ("no duplicate in-flight promotion per (application, target
-- environment)") at the database level. PromotionCommandService already performs a
-- check-then-act (SELECT active promotions, then decide, then INSERT) at the application
-- layer, but that is not atomic under concurrent requests: two requests for the same
-- (application, target environment) pair can both pass the application-level check before
-- either commits. This partial unique index makes the second concurrent INSERT fail instead
-- of silently creating two non-terminal promotions for the same target.
CREATE UNIQUE INDEX uq_promotions_active_target ON promotions (application_id, target_environment)
WHERE status NOT IN ('COMPLETED', 'ROLLED_BACK', 'CANCELLED');
