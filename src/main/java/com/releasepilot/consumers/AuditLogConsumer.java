package com.releasepilot.consumers;

import com.releasepilot.domain.promotion.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;

/**
 * Audit log consumer (SPECS §8.1): persists every {@link DomainEvent} published off the outbox
 * verbatim to the {@code audit_log} table — the system of record for "what happened when." Never
 * written to by command handlers directly; only by consuming the published event, which also
 * proves the queue path actually works end to end.
 *
 * <p>Keyed by the event's own {@code eventId} with {@code ON CONFLICT DO NOTHING} so re-delivery
 * under the outbox's at-least-once guarantee (SPECS §5.1) never produces a duplicate row.
 *
 * <p>Deliberately not a {@code @RabbitListener} bean itself — like {@link ReadModelProjector}, this
 * class depends on nothing but a {@link DomainEvent} and a {@link JdbcTemplate}, which keeps it
 * directly testable (SPECS §14.3) without a broker; the queue transport that decodes a message into
 * a {@code DomainEvent} and calls {@link #record} is a separate, thin adapter.
 */
public class AuditLogConsumer {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public AuditLogConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public void record(DomainEvent event) {
		jdbcTemplate.update(
				"""
				INSERT INTO audit_log (id, event_type, promotion_id, application_id, acting_user, occurred_at, payload)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
				ON CONFLICT (id) DO NOTHING
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
