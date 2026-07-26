package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.Role;

/**
 * Wire shape for the trusted {@code { userId, role }} actor concept (SPECS §15) carried on every
 * write request. Translated to the domain's {@link Actor} value object right here at the API
 * boundary — nothing past the controller ever sees this class.
 */
public record ActorRequest(String userId, String role) {

	public Actor toActor() {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("userId must not be blank");
		}
		if (role == null || role.isBlank()) {
			throw new IllegalArgumentException("role must not be blank");
		}
		try {
			return new Actor(userId, Role.valueOf(role.toUpperCase()));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown role '%s'".formatted(role), e);
		}
	}
}
