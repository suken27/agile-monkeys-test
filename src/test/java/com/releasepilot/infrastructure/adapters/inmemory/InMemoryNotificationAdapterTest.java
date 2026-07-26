package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryNotificationAdapterTest {

	private final InMemoryNotificationAdapter adapter = new InMemoryNotificationAdapter();

	@Test
	void recordsNoEventsBeforeAnyNotification() {
		assertThat(adapter.notifiedEvents()).isEmpty();
	}

	@Test
	void recordsEveryNotifiedEventInOrder() {
		DomainEvent completed = domainEvent("PromotionCompleted");
		DomainEvent rolledBack = domainEvent("PromotionRolledBack");

		adapter.notify(completed);
		adapter.notify(rolledBack);

		assertThat(adapter.notifiedEvents()).containsExactly(completed, rolledBack);
	}

	private static DomainEvent domainEvent(String eventType) {
		Actor actor = new Actor("user-1", Role.APPROVER);
		return DomainEvent.of(eventType, PromotionId.random(), ApplicationId.random(), actor, Map.of());
	}
}
