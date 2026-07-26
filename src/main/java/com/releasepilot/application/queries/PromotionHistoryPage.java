package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;

import java.time.Instant;
import java.util.List;

/**
 * Paged read model backing {@code GET /applications/:id/promotions} (SPECS §7).
 */
public record PromotionHistoryPage(List<PromotionHistoryItem> items, int page, int pageSize, long total) {

	public record PromotionHistoryItem(
			PromotionId id,
			Version version,
			Environment fromEnvironment,
			Environment targetEnvironment,
			PromotionStatus status,
			Instant requestedAt,
			Instant completedAt) {
	}
}
