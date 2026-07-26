package com.releasepilot.domain.promotion;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable fact recording one {@link Promotion} state transition. Carries enough data for
 * downstream consumers to act without calling back into the write side.
 */
public record DomainEvent(
		UUID eventId,
		String eventType,
		Instant occurredAt,
		PromotionId promotionId,
		ApplicationId applicationId,
		String actingUser,
		Map<String, Object> payload) {

	public static DomainEvent of(
			String eventType,
			PromotionId promotionId,
			ApplicationId applicationId,
			Actor actingActor,
			Map<String, Object> payload) {
		return new DomainEvent(
				UUID.randomUUID(),
				eventType,
				Instant.now(),
				promotionId,
				applicationId,
				actingActor.userId(),
				Map.copyOf(payload));
	}

	/**
	 * Returns a copy of this event with {@code additionalPayload} merged in. Used by command
	 * handlers to enrich an event with data only available after the aggregate transition — e.g.
	 * {@code DeploymentStarted}'s {@code deploymentRef}, only known once {@code DeploymentPort} has
	 * been called (SPECS §5).
	 */
	public DomainEvent withPayload(Map<String, Object> additionalPayload) {
		Map<String, Object> merged = new HashMap<>(payload);
		merged.putAll(additionalPayload);
		return new DomainEvent(eventId, eventType, occurredAt, promotionId, applicationId, actingUser, Map.copyOf(merged));
	}
}
