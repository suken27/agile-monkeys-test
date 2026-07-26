package com.releasepilot.api.dto.queries;

import com.releasepilot.application.queries.ApplicationEnvironmentStatus;

import java.util.List;
import java.util.UUID;

/** Response body for {@code GET /applications/:id/status} (SPECS §7). */
public record ApplicationStatusResponse(UUID applicationId, List<EnvironmentEntry> environments) {

	public static ApplicationStatusResponse from(ApplicationEnvironmentStatus status) {
		return new ApplicationStatusResponse(
				status.applicationId().value(), status.environments().stream().map(EnvironmentEntry::from).toList());
	}

	public record EnvironmentEntry(String environment, String currentVersion, String status, UUID lastPromotionId) {

		static EnvironmentEntry from(ApplicationEnvironmentStatus.EnvironmentStatus entry) {
			return new EnvironmentEntry(
					entry.environment().name(),
					entry.currentVersion() == null ? null : entry.currentVersion().value(),
					entry.status() == null ? null : entry.status().name(),
					entry.lastPromotionId() == null ? null : entry.lastPromotionId().value());
		}
	}
}
