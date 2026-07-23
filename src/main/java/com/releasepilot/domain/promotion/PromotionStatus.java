package com.releasepilot.domain.promotion;

/**
 * The lifecycle state of a {@link Promotion}. Completed, RolledBack and Cancelled are terminal:
 * no further transitions are possible once a promotion reaches one of them.
 */
public enum PromotionStatus {

	REQUESTED,
	APPROVED,
	IN_PROGRESS,
	COMPLETED,
	ROLLED_BACK,
	CANCELLED;

	public boolean isTerminal() {
		return this == COMPLETED || this == ROLLED_BACK || this == CANCELLED;
	}
}
