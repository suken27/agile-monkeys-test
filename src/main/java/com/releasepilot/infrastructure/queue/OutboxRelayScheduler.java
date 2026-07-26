package com.releasepilot.infrastructure.queue;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The poll loop {@link OutboxRelay}'s class-level javadoc anticipates: calls
 * {@link OutboxRelay#relayPendingEvents()} on a fixed delay, decoupled from the command-handling
 * call stack, so a broker hiccup here can never make an API call fail or block.
 *
 * <p>Only active under the {@code outbox-relay} Spring profile (see {@code OutboxRelayConfig} and
 * {@code application.yml}), so this relay is deployed and scaled as its own OS process/container —
 * started from the same jar/image as the API via {@code SPRING_PROFILES_ACTIVE=outbox-relay} —
 * rather than sharing the API process's call stack (SPECS §5.1, §15).
 */
@Component
@Profile("outbox-relay")
public class OutboxRelayScheduler {

	private static final long POLL_DELAY_MS = 1000;

	private final OutboxRelay outboxRelay;

	public OutboxRelayScheduler(OutboxRelay outboxRelay) {
		this.outboxRelay = outboxRelay;
	}

	@Scheduled(fixedDelay = POLL_DELAY_MS)
	public void relayPendingEvents() {
		outboxRelay.relayPendingEvents();
	}
}
