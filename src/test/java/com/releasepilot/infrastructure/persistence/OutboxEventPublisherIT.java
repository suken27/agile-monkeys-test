package com.releasepilot.infrastructure.persistence;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Role;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link OutboxEventPublisher} against a real PostgreSQL instance (via Testcontainers):
 * proves a published {@link DomainEvent} lands in {@code outbox_events} verbatim, with its payload
 * stored as real {@code jsonb} (round-trippable, not just an opaque string) and {@code published_at}
 * left {@code NULL} until {@link com.releasepilot.infrastructure.queue.OutboxRelay} delivers it.
 *
 * <p>Named {@code *IT} — see {@code JdbcPromotionRepositoryIT} for why only {@code mvnw verify} runs it.
 */
@Testcontainers
class OutboxEventPublisherIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;
	private static OutboxEventPublisher publisher;

	private final ApplicationId applicationId = ApplicationId.random();
	private final PromotionId promotionId = PromotionId.random();
	private final Actor requester = new Actor("alice", Role.REQUESTER);

	@BeforeAll
	static void migrateSchemaAndConnect() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRES.getJdbcUrl());
		config.setUsername(POSTGRES.getUsername());
		config.setPassword(POSTGRES.getPassword());
		dataSource = new HikariDataSource(config);

		Flyway.configure().dataSource(dataSource).load().migrate();

		jdbcTemplate = new JdbcTemplate(dataSource);
		publisher = new OutboxEventPublisher(jdbcTemplate, new ObjectMapper());
	}

	@AfterAll
	static void closeDataSource() {
		dataSource.close();
	}

	@Test
	void publishingAnEventInsertsAnUnpublishedOutboxRowWithTheFullPayload() {
		DomainEvent event = DomainEvent.of(
				"PromotionRequested",
				promotionId,
				applicationId,
				requester,
				Map.of("version", "1.4.0", "targetEnvironment", "DEV"));

		publisher.publish(event);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT event_type, promotion_id, application_id, acting_user, published_at, payload::text AS payload_text "
						+ "FROM outbox_events WHERE id = ?",
				event.eventId());

		assertThat(row.get("event_type")).isEqualTo("PromotionRequested");
		assertThat(row.get("promotion_id")).isEqualTo(promotionId.value());
		assertThat(row.get("application_id")).isEqualTo(applicationId.value());
		assertThat(row.get("acting_user")).isEqualTo("alice");
		assertThat(row.get("published_at")).isNull();
		assertThat((String) row.get("payload_text")).contains("\"version\"", "1.4.0", "targetEnvironment", "DEV");
	}
}
