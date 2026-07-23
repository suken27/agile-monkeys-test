package com.releasepilot.domain.promotion.errors;

import com.releasepilot.domain.promotion.Environment;

/**
 * Raised when a promotion is requested for a target environment that is not the immediate
 * next step after the version's last completed environment (invariant #1, no skipping).
 */
public final class EnvironmentSkippedError extends DomainError {

	public EnvironmentSkippedError(Environment expectedTarget, Environment requestedTarget) {
		super("Expected target environment %s but got %s".formatted(expectedTarget, requestedTarget));
	}

	public EnvironmentSkippedError(Environment requestedTarget) {
		super("Version has already completed the last pipeline environment; cannot target %s"
				.formatted(requestedTarget));
	}
}
