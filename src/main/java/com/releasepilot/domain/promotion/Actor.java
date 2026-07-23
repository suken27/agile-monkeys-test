package com.releasepilot.domain.promotion;

import java.util.Objects;

/**
 * The user performing a command, and the role they act under.
 */
public record Actor(String userId, Role role) {

	public Actor {
		Objects.requireNonNull(userId, "userId must not be null");
		if (userId.isBlank()) {
			throw new IllegalArgumentException("userId must not be blank");
		}
		Objects.requireNonNull(role, "role must not be null");
	}

	public boolean isApprover() {
		return role == Role.APPROVER;
	}
}
