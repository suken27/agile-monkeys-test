package com.releasepilot.consumers;

import com.releasepilot.application.queries.ApplicationEnvironmentStatus;
import com.releasepilot.application.queries.PromotionDetail;
import com.releasepilot.application.queries.PromotionHistoryPage;
import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Role;
import com.releasepilot.domain.promotion.Version;
import com.releasepilot.infrastructure.persistence.readmodel.JdbcPromotionReadModelRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ReadModelProjector} against a real PostgreSQL instance (via Testcontainers) by
 * feeding it the same {@link DomainEvent}s a promotion's full lifecycle would produce, then reading
 * the result back through {@link JdbcPromotionReadModelRepository} — proving the projector writes
 * exactly the shape the query side (SPECS §7) expects. This is the "consumer test" from SPECS §14.3:
 * feed a {@code DomainEvent} in, assert the read model is correct.
 *
 * <p>Named {@code *IT} rather than {@code *Test} — see {@code JdbcPromotionRepositoryIT} for why
 * only {@code mvnw verify} runs it.
 */
@Testcontainers
class ReadModelProjectorIT {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;
	private static ReadModelProjector projector;
	private static JdbcPromotionReadModelRepository readModel;

	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("1.0.0");
	private final Actor alice = new Actor("alice", Role.REQUESTER);
	private final Actor bob = new Actor("bob", Role.APPROVER);

	@BeforeAll
	static void migrateSchemaAndConnect() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRES.getJdbcUrl());
		config.setUsername(POSTGRES.getUsername());
		config.setPassword(POSTGRES.getPassword());
		dataSource = new HikariDataSource(config);

		Flyway.configure().dataSource(dataSource).load().migrate();

		jdbcTemplate = new JdbcTemplate(dataSource);
		projector = new ReadModelProjector(jdbcTemplate);
		readModel = new JdbcPromotionReadModelRepository(jdbcTemplate);
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

	@Test
	void projectsAFullHappyPathLifecycleIntoAllThreeReadModels() {
		PromotionId promotionId = PromotionId.random();

		requested(promotionId, null, Environment.DEV);
		project(Promotion.EVENT_PROMOTION_APPROVED, promotionId, bob, Map.of("approvedBy", "bob"));
		project(Promotion.EVENT_DEPLOYMENT_STARTED, promotionId, bob, Map.of("deploymentRef", "deploy-1"));
		project(Promotion.EVENT_PROMOTION_COMPLETED, promotionId, bob, Map.of());

		PromotionDetail detail = readModel.findPromotionDetail(promotionId).orElseThrow();
		assertThat(detail.applicationId()).isEqualTo(applicationId);
		assertThat(detail.version()).isEqualTo(version);
		assertThat(detail.fromEnvironment()).isNull();
		assertThat(detail.targetEnvironment()).isEqualTo(Environment.DEV);
		assertThat(detail.status()).isEqualTo(PromotionStatus.COMPLETED);
		assertThat(detail.requestedBy()).isEqualTo("alice");
		assertThat(detail.approvedBy()).isEqualTo("bob");
		assertThat(detail.history()).extracting(PromotionDetail.PromotionHistoryEntry::status).containsExactly(
				PromotionStatus.REQUESTED, PromotionStatus.APPROVED, PromotionStatus.IN_PROGRESS,
				PromotionStatus.COMPLETED);

		ApplicationEnvironmentStatus status = readModel.findApplicationEnvironmentStatus(applicationId);
		var dev = status.environments().stream().filter(e -> e.environment() == Environment.DEV).findFirst()
				.orElseThrow();
		assertThat(dev.status()).isEqualTo(PromotionStatus.COMPLETED);
		assertThat(dev.currentVersion()).isEqualTo(version);
		assertThat(dev.lastPromotionId()).isEqualTo(promotionId);

		PromotionHistoryPage page = readModel.findApplicationPromotions(applicationId, 0, 10);
		assertThat(page.items()).hasSize(1);
		var item = page.items().get(0);
		assertThat(item.status()).isEqualTo(PromotionStatus.COMPLETED);
		assertThat(item.completedAt()).isNotNull();
	}

	@Test
	void cancellingBeforeDeploymentLeavesTheEnvironmentsCurrentVersionUntouched() {
		PromotionId promotionId = PromotionId.random();

		requested(promotionId, null, Environment.DEV);
		project(Promotion.EVENT_PROMOTION_CANCELLED, promotionId, alice, Map.of("reason", "changed my mind"));

		PromotionDetail detail = readModel.findPromotionDetail(promotionId).orElseThrow();
		assertThat(detail.status()).isEqualTo(PromotionStatus.CANCELLED);
		assertThat(detail.history()).extracting(PromotionDetail.PromotionHistoryEntry::status).containsExactly(
				PromotionStatus.REQUESTED, PromotionStatus.CANCELLED);

		var dev = readModel.findApplicationEnvironmentStatus(applicationId).environments().stream()
				.filter(e -> e.environment() == Environment.DEV).findFirst().orElseThrow();
		assertThat(dev.status()).isEqualTo(PromotionStatus.CANCELLED);
		assertThat(dev.currentVersion()).isNull();
		assertThat(dev.lastPromotionId()).isEqualTo(promotionId);
	}

	@Test
	void rollingBackAfterDeploymentStartedDoesNotAdvanceTheEnvironmentsCurrentVersion() {
		PromotionId promotionId = PromotionId.random();

		requested(promotionId, null, Environment.DEV);
		project(Promotion.EVENT_PROMOTION_APPROVED, promotionId, bob, Map.of("approvedBy", "bob"));
		project(Promotion.EVENT_DEPLOYMENT_STARTED, promotionId, bob, Map.of("deploymentRef", "deploy-1"));
		project(Promotion.EVENT_PROMOTION_ROLLED_BACK, promotionId, bob, Map.of("reason", "bad build"));

		var dev = readModel.findApplicationEnvironmentStatus(applicationId).environments().stream()
				.filter(e -> e.environment() == Environment.DEV).findFirst().orElseThrow();
		assertThat(dev.status()).isEqualTo(PromotionStatus.ROLLED_BACK);
		assertThat(dev.currentVersion()).isNull();

		PromotionHistoryPage page = readModel.findApplicationPromotions(applicationId, 0, 10);
		assertThat(page.items().get(0).status()).isEqualTo(PromotionStatus.ROLLED_BACK);
		assertThat(page.items().get(0).completedAt()).isNotNull();
	}

	@Test
	void aSecondHopCarriesItsFromEnvironmentAndGetsItsOwnEnvironmentStatusRow() {
		PromotionId devPromotionId = PromotionId.random();
		requested(devPromotionId, null, Environment.DEV);
		project(Promotion.EVENT_PROMOTION_APPROVED, devPromotionId, bob, Map.of("approvedBy", "bob"));
		project(Promotion.EVENT_DEPLOYMENT_STARTED, devPromotionId, bob, Map.of("deploymentRef", "deploy-1"));
		project(Promotion.EVENT_PROMOTION_COMPLETED, devPromotionId, bob, Map.of());

		PromotionId stagingPromotionId = PromotionId.random();
		requested(stagingPromotionId, Environment.DEV, Environment.STAGING);

		PromotionDetail stagingDetail = readModel.findPromotionDetail(stagingPromotionId).orElseThrow();
		assertThat(stagingDetail.fromEnvironment()).isEqualTo(Environment.DEV);
		assertThat(stagingDetail.targetEnvironment()).isEqualTo(Environment.STAGING);

		ApplicationEnvironmentStatus status = readModel.findApplicationEnvironmentStatus(applicationId);
		var dev = status.environments().stream().filter(e -> e.environment() == Environment.DEV).findFirst()
				.orElseThrow();
		assertThat(dev.status()).isEqualTo(PromotionStatus.COMPLETED);
		assertThat(dev.currentVersion()).isEqualTo(version);

		var staging = status.environments().stream().filter(e -> e.environment() == Environment.STAGING).findFirst()
				.orElseThrow();
		assertThat(staging.status()).isEqualTo(PromotionStatus.REQUESTED);
		assertThat(staging.currentVersion()).isNull();
		assertThat(staging.lastPromotionId()).isEqualTo(stagingPromotionId);
	}

	private void requested(PromotionId promotionId, Environment fromEnvironment, Environment targetEnvironment) {
		Map<String, Object> payload = fromEnvironment == null
				? Map.of("version", version.value(), "targetEnvironment", targetEnvironment.name())
				: Map.of(
						"version", version.value(), "fromEnvironment", fromEnvironment.name(), "targetEnvironment",
						targetEnvironment.name());
		project(Promotion.EVENT_PROMOTION_REQUESTED, promotionId, alice, payload);
	}

	private void project(String eventType, PromotionId promotionId, Actor actor, Map<String, Object> payload) {
		projector.project(DomainEvent.of(eventType, promotionId, applicationId, actor, payload));
	}
}
