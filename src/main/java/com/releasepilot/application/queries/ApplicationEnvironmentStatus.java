package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;

import java.util.List;

/**
 * Read model backing {@code GET /applications/:id/status} (SPECS §7). Always carries one entry
 * per pipeline stage (§3.1) — an environment the application has never had a promotion target
 * gets a {@link EnvironmentStatus} with every field {@code null} rather than being omitted, so
 * callers can render the full pipeline without special-casing missing stages.
 */
public record ApplicationEnvironmentStatus(ApplicationId applicationId, List<EnvironmentStatus> environments) {

	public record EnvironmentStatus(
			Environment environment, Version currentVersion, PromotionStatus status, PromotionId lastPromotionId) {
	}
}
