package com.releasepilot.consumers;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Role;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Exercises {@link AuditLogConsumer} against a real PostgreSQL instance (via Testcontainers),
 * proving a consumed {@link DomainEvent} lands in {@code audit_log} verbatim, with its payload
 * stored as real {@code jsonb}, and that re-delivering the same event (SPECS §5.1's at-least-once
 * guarantee) never produces a duplicate row. This is the "consumer test" from SPECS §14.3: feed a
 * {@code DomainEvent} in, assert the audit row is correct.
 *
 * <p>Named {@code *IT} rather than {@code *Test} — see {@code JdbcPromotionRepositoryIT} for why
 * only {@code mvnw verify} runs it.
 */
@Testcontainers
class AuditLogConsumerIT {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;
	private static AuditLogConsumer consumer;

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
		consumer = new AuditLogConsumer(jdbcTemplate, new ObjectMapper());
	}

	@AfterAll
	static void closeDataSource() {
		dataSource.close();
	}

	@BeforeEach
	void cleanTable() {
		jdbcTemplate.update("DELETE FROM audit_log");
	}

	@Test
	void recordingAnEventInsertsAnAuditRowWithTheFullPayload() {
		DomainEvent event = DomainEvent.of(
				Promotion.EVENT_PROMOTION_REQUESTED, promotionId, applicationId, requester,
				Map.of("version", "1.4.0", "targetEnvironment", "DEV"));

		consumer.record(event);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT event_type, promotion_id, application_id, acting_user, payload::text AS payload_text "
						+ "FROM audit_log WHERE id = ?",
				event.eventId());

		assertThat(row.get("event_type")).isEqualTo(Promotion.EVENT_PROMOTION_REQUESTED);
		assertThat(row.get("promotion_id")).isEqualTo(promotionId.value());
		assertThat(row.get("application_id")).isEqualTo(applicationId.value());
		assertThat(row.get("acting_user")).isEqualTo("alice");
		assertThat((String) row.get("payload_text")).contains("\"version\"", "1.4.0", "targetEnvironment", "DEV");
	}

	@Test
	void recordingTheSameEventTwiceNeverProducesADuplicateRow() {
		DomainEvent event = DomainEvent.of(
				Promotion.EVENT_PROMOTION_APPROVED, promotionId, applicationId, requester,
				Map.of("approvedBy", "bob"));

		consumer.record(event);
		consumer.record(event);

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_log WHERE id = ?", Integer.class, event.eventId());
		assertThat(count).isEqualTo(1);
	}
}
