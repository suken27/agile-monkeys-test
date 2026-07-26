package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;

/** Request body for {@code POST /promotions/:id/approve} (SPECS §10 → {@code ApprovePromotion}). */
public record ApprovePromotionRequest(ActorRequest approvedBy) {

	public Actor approvedByActor() {
		if (approvedBy == null) {
			throw new IllegalArgumentException("approvedBy must not be null");
		}
		return approvedBy.toActor();
	}
}
