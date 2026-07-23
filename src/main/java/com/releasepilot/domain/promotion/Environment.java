package com.releasepilot.domain.promotion;

import java.util.Optional;

/**
 * A stage in the deployment pipeline. Fixed, ordered: dev -> staging -> production.
 */
public enum Environment {

	DEV,
	STAGING,
	PRODUCTION;

	public static Environment first() {
		return DEV;
	}

	public Optional<Environment> next() {
		return switch (this) {
			case DEV -> Optional.of(STAGING);
			case STAGING -> Optional.of(PRODUCTION);
			case PRODUCTION -> Optional.empty();
		};
	}

	public Optional<Environment> previous() {
		return switch (this) {
			case DEV -> Optional.empty();
			case STAGING -> Optional.of(DEV);
			case PRODUCTION -> Optional.of(STAGING);
		};
	}
}
