package com.releasepilot.domain.promotion.errors;

/**
 * Base type for every business rule violation raised by the {@code Promotion} aggregate.
 * Domain errors are always mapped to 4xx responses at the API boundary — they never surface
 * as uncaught exceptions (never 500).
 */
public abstract sealed class DomainError extends RuntimeException permits
		InvalidTransitionError,
		EnvironmentSkippedError,
		UnauthorizedApproverError,
		PromotionAlreadyTerminalError,
		DuplicatePromotionInProgressError {

	protected DomainError(String message) {
		super(message);
	}
}
