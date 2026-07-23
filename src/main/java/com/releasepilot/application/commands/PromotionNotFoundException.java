package com.releasepilot.application.commands;

import com.releasepilot.domain.promotion.PromotionId;

/** Raised when a command targets a {@code promotionId} that does not exist. */
public final class PromotionNotFoundException extends RuntimeException {

	public PromotionNotFoundException(PromotionId promotionId) {
		super("No promotion found with id %s".formatted(promotionId.value()));
	}
}
