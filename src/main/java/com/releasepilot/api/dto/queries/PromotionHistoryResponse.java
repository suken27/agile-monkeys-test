package com.releasepilot.api.dto.queries;

import com.releasepilot.application.queries.PromotionHistoryPage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response body for {@code GET /applications/:id/promotions} (SPECS §7). */
public record PromotionHistoryResponse(List<Item> items, int page, int pageSize, long total) {

	public static PromotionHistoryResponse from(PromotionHistoryPage page) {
		return new PromotionHistoryResponse(
				page.items().stream().map(Item::from).toList(), page.page(), page.pageSize(), page.total());
	}

	public record Item(
			UUID id,
			String version,
			String fromEnvironment,
			String targetEnvironment,
			String status,
			Instant requestedAt,
			Instant completedAt) {

		static Item from(PromotionHistoryPage.PromotionHistoryItem item) {
			return new Item(
					item.id().value(),
					item.version().value(),
					item.fromEnvironment() == null ? null : item.fromEnvironment().name(),
					item.targetEnvironment().name(),
					item.status().name(),
					item.requestedAt(),
					item.completedAt());
		}
	}
}
