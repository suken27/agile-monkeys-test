package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;

/** Request body for {@code POST /promotions/:id/rollback} (SPECS §10 → {@code RollbackPromotion}). */
public record RollbackPromotionRequest(ActorRequest rolledBackBy, String reason) {

	public Actor rolledBackByActor() {
		if (rolledBackBy == null) {
			throw new IllegalArgumentException("rolledBackBy must not be null");
		}
		return rolledBackBy.toActor();
	}
}
