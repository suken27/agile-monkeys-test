package com.releasepilot.infrastructure.persistence;

import com.releasepilot.domain.ports.EventPublisherPort;
import com.releasepilot.domain.promotion.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;

/**
 * JDBC implementation of {@link EventPublisherPort} — the write side of the transactional-outbox
 * pattern (SPECS §5.1). Inserts one row per event into the {@code outbox_events} table
 * (see {@code db/migration/V2__create_outbox_events_table.sql}) via plain {@link JdbcTemplate},
 * using the same connection/transaction the command handler's {@code PromotionRepositoryPort.save}
 * call runs in, so the aggregate's new state and its event are committed together or not at all.
 * {@link com.releasepilot.infrastructure.queue.OutboxRelay} is the separate, decoupled component
 * that later reads unsent rows here and delivers them to the message broker.
 */
@Component
public class OutboxEventPublisher implements EventPublisherPort {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public OutboxEventPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void publish(DomainEvent event) {
		jdbcTemplate.update(
				"""
				INSERT INTO outbox_events (
				    id, event_type, promotion_id, application_id, acting_user, occurred_at, payload
				) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
				""",
				event.eventId(),
				event.eventType(),
				event.promotionId().value(),
				event.applicationId().value(),
				event.actingUser(),
				Timestamp.from(event.occurredAt()),
				objectMapper.writeValueAsString(event.payload()));
	}
}
