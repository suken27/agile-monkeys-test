package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.DomainEvent;

/**
 * Output port: a capability the domain requires of the outside world to alert on terminal-state
 * events (Completed, RolledBack, Cancelled). Implemented by an infrastructure adapter.
 */
public interface NotificationPort {

	void notify(DomainEvent event);
}
