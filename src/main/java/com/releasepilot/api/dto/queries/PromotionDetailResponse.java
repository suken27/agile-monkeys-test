package com.releasepilot.api.dto.queries;

import com.releasepilot.application.queries.PromotionDetail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response body for {@code GET /promotions/:id} (SPECS §7), flattening domain value objects to JSON primitives. */
public record PromotionDetailResponse(
		UUID id,
		UUID applicationId,
		String version,
		String fromEnvironment,
		String targetEnvironment,
		String status,
		String requestedBy,
		String approvedBy,
		List<HistoryEntry> history) {

	public static PromotionDetailResponse from(PromotionDetail detail) {
		return new PromotionDetailResponse(
				detail.id().value(),
				detail.applicationId().value(),
				detail.version().value(),
				detail.fromEnvironment() == null ? null : detail.fromEnvironment().name(),
				detail.targetEnvironment().name(),
				detail.status().name(),
				detail.requestedBy(),
				detail.approvedBy(),
				detail.history().stream().map(HistoryEntry::from).toList());
	}

	public record HistoryEntry(String status, String actor, Instant occurredAt) {

		static HistoryEntry from(PromotionDetail.PromotionHistoryEntry entry) {
			return new HistoryEntry(entry.status().name(), entry.actor(), entry.occurredAt());
		}
	}
}
