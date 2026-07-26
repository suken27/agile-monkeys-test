package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Version;

/** Request body for {@code POST /promotions} (SPECS §10 → {@code RequestPromotion}, §4). */
public record RequestPromotionRequest(
		String applicationId, String version, String targetEnvironment, ActorRequest requestedBy) {

	public ApplicationId applicationIdValue() {
		if (applicationId == null || applicationId.isBlank()) {
			throw new IllegalArgumentException("applicationId must not be blank");
		}
		return ApplicationId.of(applicationId);
	}

	public Version versionValue() {
		return new Version(version);
	}

	public Environment targetEnvironmentValue() {
		if (targetEnvironment == null || targetEnvironment.isBlank()) {
			throw new IllegalArgumentException("targetEnvironment must not be blank");
		}
		try {
			return Environment.valueOf(targetEnvironment.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown targetEnvironment '%s'".formatted(targetEnvironment), e);
		}
	}

	public Actor requestedByActor() {
		if (requestedBy == null) {
			throw new IllegalArgumentException("requestedBy must not be null");
		}
		return requestedBy.toActor();
	}
}
