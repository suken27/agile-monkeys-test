package com.releasepilot.domain.promotion;

import java.time.Instant;
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
}
