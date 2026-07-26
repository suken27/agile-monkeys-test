package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.DomainEvent;

/**
 * Output port: the capability the application layer requires to reliably hand off a
 * {@link DomainEvent} recorded by the {@code Promotion} aggregate for eventual delivery to the
 * message queue. A call returning normally is a durability guarantee, not a delivery one — the
 * transactional-outbox adapter (SPECS §5.1) persists the event in the same DB transaction as the
 * aggregate's new state; a separate relay delivers it to the broker afterwards, decoupled from the
 * command-handling call stack.
 */
public interface EventPublisherPort {

	void publish(DomainEvent event);
}
