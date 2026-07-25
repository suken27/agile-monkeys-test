package com.releasepilot.domain.promotion.errors;

/**
 * Raised when {@code ApprovePromotion} is attempted by an actor without the approver role.
 */
public final class UnauthorizedApproverError extends DomainError {

	public UnauthorizedApproverError(String userId) {
		super("User '%s' does not have the approver role".formatted(userId));
	}
}
