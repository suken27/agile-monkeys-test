package com.releasepilot.application;

import com.releasepilot.domain.promotion.PromotionId;

/**
 * Raised when a command or query targets a {@code promotionId} that does not exist. Shared by
 * both the command and query application services, since "not found" is not itself a business
 * rule violation raised by the {@code Promotion} aggregate (it never gets the chance to load).
 */
public final class PromotionNotFoundException extends RuntimeException {

	public PromotionNotFoundException(PromotionId promotionId) {
		super("No promotion found with id %s".formatted(promotionId.value()));
	}
}
