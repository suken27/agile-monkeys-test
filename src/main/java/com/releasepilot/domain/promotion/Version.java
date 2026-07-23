package com.releasepilot.domain.promotion;

/**
 * An opaque, immutable build/artifact identifier for an application (e.g. semver or commit SHA).
 */
public record Version(String value) {

	public Version {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Version value must not be blank");
		}
	}
}
