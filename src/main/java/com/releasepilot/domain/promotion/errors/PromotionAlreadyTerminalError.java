package com.releasepilot.domain.promotion.errors;

import com.releasepilot.domain.promotion.PromotionStatus;

/**
 * Raised when any command is issued against a promotion already in a terminal state
 * (Completed, RolledBack or Cancelled). No field on a terminal promotion may change.
 */
public final class PromotionAlreadyTerminalError extends DomainError {

	public PromotionAlreadyTerminalError(PromotionStatus status) {
		super("Promotion is already in terminal status %s and cannot be modified".formatted(status));
	}
}
