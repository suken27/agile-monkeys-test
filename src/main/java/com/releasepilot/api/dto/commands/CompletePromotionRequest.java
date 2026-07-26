package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;

/** Request body for {@code POST /promotions/:id/complete} (SPECS §10 → {@code CompletePromotion}). */
public record CompletePromotionRequest(ActorRequest completedBy) {

	public Actor completedByActor() {
		if (completedBy == null) {
			throw new IllegalArgumentException("completedBy must not be null");
		}
		return completedBy.toActor();
	}
}
