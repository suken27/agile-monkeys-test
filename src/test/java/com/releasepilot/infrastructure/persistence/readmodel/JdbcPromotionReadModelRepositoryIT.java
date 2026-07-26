package com.releasepilot.infrastructure.persistence.readmodel;

import com.releasepilot.application.queries.ApplicationEnvironmentStatus;
import com.releasepilot.application.queries.PromotionDetail;
import com.releasepilot.application.queries.PromotionHistoryPage;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
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

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link JdbcPromotionReadModelRepository} against a real PostgreSQL instance (via
 * Testcontainers). Since the read-model projector consumer that would normally populate these
 * tables (SPECS §8) does not exist yet, rows are seeded directly with {@link JdbcTemplate} —
 * exactly the shape a projector would write — to prove the read-side queries (SPECS §7) are
 * correct against that schema.
 *
 * <p>Named {@code *IT} rather than {@code *Test} — see {@code JdbcPromotionRepositoryIT} for why
 * only {@code mvnw verify} runs it.
 */
@Testcontainers
class JdbcPromotionReadModelRepositoryIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;
	private static JdbcPromotionReadModelRepository repository;

	private final ApplicationId applicationId = ApplicationId.random();

	@BeforeAll
	static void migrateSchemaAndConnect() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRES.getJdbcUrl());
		config.setUsername(POSTGRES.getUsername());
		config.setPassword(POSTGRES.getPassword());
		dataSource = new HikariDataSource(config);

		Flyway.configure().dataSource(dataSource).load().migrate();

		jdbcTemplate = new JdbcTemplate(dataSource);
		repository = new JdbcPromotionReadModelRepository(jdbcTemplate);
	}

	@AfterAll
	static void closeDataSource() {
		dataSource.close();
	}

	@BeforeEach
	void cleanTables() {
		jdbcTemplate.update("DELETE FROM promotion_detail_history");
		jdbcTemplate.update("DELETE FROM promotion_detail");
		jdbcTemplate.update("DELETE FROM application_environment_status");
		jdbcTemplate.update("DELETE FROM promotion_history");
	}

	// --- findPromotionDetail ---

	@Test
	void findPromotionDetailReturnsEmptyForAnUnknownId() {
		assertThat(repository.findPromotionDetail(PromotionId.random())).isEmpty();
	}

	@Test
	void findPromotionDetailReturnsTheRowWithItsOrderedHistory() {
		PromotionId promotionId = PromotionId.random();
		seedPromotionDetail(promotionId, "APPROVED", "bob");
		seedHistoryEntry(promotionId, "REQUESTED", "alice", Instant.parse("2026-01-01T00:00:00Z"));
		seedHistoryEntry(promotionId, "APPROVED", "bob", Instant.parse("2026-01-02T00:00:00Z"));

		PromotionDetail detail = repository.findPromotionDetail(promotionId).orElseThrow();

		assertThat(detail.id()).isEqualTo(promotionId);
		assertThat(detail.applicationId()).isEqualTo(applicationId);
		assertThat(detail.status()).isEqualTo(PromotionStatus.APPROVED);
		assertThat(detail.requestedBy()).isEqualTo("alice");
		assertThat(detail.approvedBy()).isEqualTo("bob");
		assertThat(detail.history()).extracting(PromotionDetail.PromotionHistoryEntry::status)
				.containsExactly(PromotionStatus.REQUESTED, PromotionStatus.APPROVED);
	}

	// --- findApplicationEnvironmentStatus ---

	@Test
	void findApplicationEnvironmentStatusAlwaysReturnsAllThreeEnvironments() {
		ApplicationEnvironmentStatus status = repository.findApplicationEnvironmentStatus(applicationId);

		assertThat(status.environments()).extracting(ApplicationEnvironmentStatus.EnvironmentStatus::environment)
				.containsExactly(Environment.DEV, Environment.STAGING, Environment.PRODUCTION);
		assertThat(status.environments()).allSatisfy(env -> assertThat(env.status()).isNull());
	}

	@Test
	void findApplicationEnvironmentStatusFillsInKnownEnvironmentsAndDefaultsTheRest() {
		PromotionId lastPromotionId = PromotionId.random();
		jdbcTemplate.update(
				"""
				INSERT INTO application_environment_status
				    (application_id, environment, current_version, status, last_promotion_id)
				VALUES (?, 'DEV', '1.4.0', 'COMPLETED', ?)
				""",
				applicationId.value(),
				lastPromotionId.value());

		ApplicationEnvironmentStatus status = repository.findApplicationEnvironmentStatus(applicationId);

		var dev = status.environments().stream().filter(e -> e.environment() == Environment.DEV).findFirst()
				.orElseThrow();
		assertThat(dev.currentVersion().value()).isEqualTo("1.4.0");
		assertThat(dev.status()).isEqualTo(PromotionStatus.COMPLETED);
		assertThat(dev.lastPromotionId()).isEqualTo(lastPromotionId);

		var staging = status.environments().stream().filter(e -> e.environment() == Environment.STAGING).findFirst()
				.orElseThrow();
		assertThat(staging.currentVersion()).isNull();
		assertThat(staging.status()).isNull();
		assertThat(staging.lastPromotionId()).isNull();
	}

	// --- findApplicationPromotions ---

	@Test
	void findApplicationPromotionsPagesNewestFirstAndReportsTheTotal() {
		seedPromotionHistory(applicationId, "1.0.0", Instant.parse("2026-01-01T00:00:00Z"));
		seedPromotionHistory(applicationId, "1.1.0", Instant.parse("2026-01-02T00:00:00Z"));
		seedPromotionHistory(applicationId, "1.2.0", Instant.parse("2026-01-03T00:00:00Z"));

		PromotionHistoryPage firstPage = repository.findApplicationPromotions(applicationId, 0, 2);
		assertThat(firstPage.total()).isEqualTo(3);
		assertThat(firstPage.items()).hasSize(2);
		assertThat(firstPage.items().get(0).version().value()).isEqualTo("1.2.0");
		assertThat(firstPage.items().get(1).version().value()).isEqualTo("1.1.0");

		PromotionHistoryPage secondPage = repository.findApplicationPromotions(applicationId, 1, 2);
		assertThat(secondPage.items()).hasSize(1);
		assertThat(secondPage.items().get(0).version().value()).isEqualTo("1.0.0");
	}

	@Test
	void findApplicationPromotionsReturnsAnEmptyPageForAnApplicationWithNoHistory() {
		PromotionHistoryPage page = repository.findApplicationPromotions(ApplicationId.random(), 0, 20);

		assertThat(page.items()).isEmpty();
		assertThat(page.total()).isZero();
	}

	private void seedPromotionDetail(PromotionId promotionId, String status, String approvedBy) {
		jdbcTemplate.update(
				"""
				INSERT INTO promotion_detail
				    (id, application_id, version, from_environment, target_environment, status,
				     requested_by_user_id, approved_by_user_id)
				VALUES (?, ?, '1.0.0', NULL, 'DEV', ?, 'alice', ?)
				""",
				promotionId.value(),
				applicationId.value(),
				status,
				approvedBy);
	}

	private void seedHistoryEntry(PromotionId promotionId, String status, String actor, Instant occurredAt) {
		jdbcTemplate.update(
				"""
				INSERT INTO promotion_detail_history (promotion_id, status, actor_user_id, occurred_at)
				VALUES (?, ?, ?, ?)
				""",
				promotionId.value(),
				status,
				actor,
				Timestamp.from(occurredAt));
	}

	private void seedPromotionHistory(ApplicationId applicationId, String version, Instant requestedAt) {
		jdbcTemplate.update(
				"""
				INSERT INTO promotion_history
				    (id, application_id, version, from_environment, target_environment, status, requested_at)
				VALUES (?, ?, ?, NULL, 'DEV', 'REQUESTED', ?)
				""",
				UUID.randomUUID(),
				applicationId.value(),
				version,
				Timestamp.from(requestedAt));
	}
}
