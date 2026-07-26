package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;

/** Request body for {@code POST /promotions/:id/cancel} (SPECS §10 → {@code CancelPromotion}). */
public record CancelPromotionRequest(ActorRequest cancelledBy, String reason) {

	public Actor cancelledByActor() {
		if (cancelledBy == null) {
			throw new IllegalArgumentException("cancelledBy must not be null");
		}
		return cancelledBy.toActor();
	}
}
