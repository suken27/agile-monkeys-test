package com.releasepilot.infrastructure.queue;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads rows the {@link com.releasepilot.infrastructure.persistence.OutboxEventPublisher} wrote to
 * {@code outbox_events} that have not yet been delivered, publishes each to the
 * {@value #PROMOTION_EVENTS_EXCHANGE} fanout exchange, and marks it as sent. This is the second
 * half of the transactional-outbox pattern (SPECS §5.1): it runs decoupled from the command-
 * handling call stack (its own poll loop/process in production), so a broker hiccup can never make
 * an API call fail or block.
 *
 * <p>The fanout exchange means every consumer declares and binds its own queue to it — this relay
 * does not need to know how many consumers exist or what they're called.
 */
public class OutboxRelay {

	public static final String PROMOTION_EVENTS_EXCHANGE = "promotion.events";

	private static final RowMapper<OutboxRow> ROW_MAPPER = OutboxRelay::mapRow;

	private final JdbcTemplate jdbcTemplate;
	private final RabbitTemplate rabbitTemplate;
	private final ObjectMapper objectMapper;

	public OutboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.rabbitTemplate = rabbitTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Publishes every outbox row not yet sent, oldest first, marking each as sent immediately after
	 * the broker accepts it. Returns how many rows were relayed.
	 */
	public int relayPendingEvents() {
		List<OutboxRow> pending = jdbcTemplate.query(
				"""
				SELECT id, event_type, promotion_id, application_id, acting_user, occurred_at, payload
				FROM outbox_events
				WHERE published_at IS NULL
				ORDER BY occurred_at
				""",
				ROW_MAPPER);

		for (OutboxRow row : pending) {
			rabbitTemplate.send(PROMOTION_EVENTS_EXCHANGE, "", toMessage(row));
			jdbcTemplate.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", row.id());
		}

		return pending.size();
	}

	private Message toMessage(OutboxRow row) {
		Map<String, Object> payload = objectMapper.readValue(row.payloadJson(), new TypeReference<Map<String, Object>>() {
		});

		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("eventId", row.id().toString());
		envelope.put("eventType", row.eventType());
		envelope.put("occurredAt", row.occurredAt().toString());
		envelope.put("promotionId", row.promotionId().toString());
		envelope.put("applicationId", row.applicationId().toString());
		envelope.put("actingUser", row.actingUser());
		envelope.put("payload", payload);

		byte[] body = objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
		return MessageBuilder.withBody(body).setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
	}

	private static OutboxRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new OutboxRow(
				rs.getObject("id", UUID.class),
				rs.getString("event_type"),
				rs.getObject("promotion_id", UUID.class),
				rs.getObject("application_id", UUID.class),
				rs.getString("acting_user"),
				rs.getTimestamp("occurred_at").toInstant(),
				rs.getString("payload"));
	}

	private record OutboxRow(
			UUID id,
			String eventType,
			UUID promotionId,
			UUID applicationId,
			String actingUser,
			Instant occurredAt,
			String payloadJson) {
	}
}
