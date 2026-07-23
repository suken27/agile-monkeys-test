package com.releasepilot.domain.promotion;

import java.util.Objects;
import java.util.UUID;

public record ApplicationId(UUID value) {

	public ApplicationId {
		Objects.requireNonNull(value, "ApplicationId value must not be null");
	}

	public static ApplicationId of(String value) {
		return new ApplicationId(UUID.fromString(value));
	}

	public static ApplicationId random() {
		return new ApplicationId(UUID.randomUUID());
	}
}
