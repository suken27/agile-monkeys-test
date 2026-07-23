package com.releasepilot.domain.promotion.errors;

import com.releasepilot.domain.promotion.PromotionStatus;

/**
 * Raised when a command is issued against a promotion whose current (non-terminal) status
 * does not permit that transition (e.g. cancelling a promotion that is already in progress).
 */
public final class InvalidTransitionError extends DomainError {

	public InvalidTransitionError(PromotionStatus from, String command) {
		super("Cannot apply command '%s' to a promotion in status %s".formatted(command, from));
	}
}
