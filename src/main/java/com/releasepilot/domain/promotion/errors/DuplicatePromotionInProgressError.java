package com.releasepilot.domain.promotion.errors;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;

/**
 * Raised when a promotion is requested for an (application, target environment) pair that
 * already has a non-terminal promotion in flight (invariant #2).
 */
public final class DuplicatePromotionInProgressError extends DomainError {

	public DuplicatePromotionInProgressError(ApplicationId applicationId, Environment targetEnvironment) {
		super("A non-terminal promotion already exists for application %s targeting %s"
				.formatted(applicationId.value(), targetEnvironment));
	}
}
