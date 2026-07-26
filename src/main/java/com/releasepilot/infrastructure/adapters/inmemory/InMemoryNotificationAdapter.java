package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.ports.NotificationPort;
import com.releasepilot.domain.promotion.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stub adapter for {@link NotificationPort} (SPECS §6). Records every notified event in
 * memory so tests can assert on it — there is no outbound HTTP in this exercise; a real
 * notification client (email, Slack, etc.) would implement the same port.
 */
@Component
public class InMemoryNotificationAdapter implements NotificationPort {

	private final List<DomainEvent> notifiedEvents = new CopyOnWriteArrayList<>();

	@Override
	public void notify(DomainEvent event) {
		notifiedEvents.add(event);
	}

	/** The events notified so far, oldest first — for test assertions. */
	public List<DomainEvent> notifiedEvents() {
		return List.copyOf(notifiedEvents);
	}
}
