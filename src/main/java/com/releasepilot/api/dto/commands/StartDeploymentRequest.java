package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;

/** Request body for {@code POST /promotions/:id/start} (SPECS §10 → {@code StartDeployment}). */
public record StartDeploymentRequest(ActorRequest startedBy) {

	public Actor startedByActor() {
		if (startedBy == null) {
			throw new IllegalArgumentException("startedBy must not be null");
		}
		return startedBy.toActor();
	}
}
