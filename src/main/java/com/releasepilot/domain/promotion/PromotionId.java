package com.releasepilot.domain.promotion;

import java.util.Objects;
import java.util.UUID;

public record PromotionId(UUID value) {

	public PromotionId {
		Objects.requireNonNull(value, "PromotionId value must not be null");
	}

	public static PromotionId of(String value) {
		return new PromotionId(UUID.fromString(value));
	}

	public static PromotionId random() {
		return new PromotionId(UUID.randomUUID());
	}
}
