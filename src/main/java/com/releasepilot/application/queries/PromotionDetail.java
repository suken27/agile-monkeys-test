package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;

import java.time.Instant;
import java.util.List;

/**
 * Read model backing {@code GET /promotions/:id} (SPECS §7). Shaped for its one consumer, not for
 * the {@code Promotion} aggregate's internal representation — {@link #history} in particular is
 * built by the read-model projector appending one row per consumed event (SPECS §8), not
 * recomputed from the write side on read.
 */
public record PromotionDetail(
		PromotionId id,
		ApplicationId applicationId,
		Version version,
		Environment fromEnvironment,
		Environment targetEnvironment,
		PromotionStatus status,
		String requestedBy,
		String approvedBy,
		List<PromotionHistoryEntry> history) {

	public record PromotionHistoryEntry(PromotionStatus status, String actor, Instant occurredAt) {
	}
}
