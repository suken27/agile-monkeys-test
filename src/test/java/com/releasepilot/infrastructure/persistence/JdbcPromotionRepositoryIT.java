package com.releasepilot.infrastructure.persistence;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Role;
import com.releasepilot.domain.promotion.Version;
import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link JdbcPromotionRepository} against a real PostgreSQL instance (via Testcontainers)
 * and the real {@link Promotion} aggregate — no mocks on either side — to prove the write-side
 * repository and the domain layer agree on what a {@code Promotion} looks like on the wire, and
 * that the two invariant queries (§3.3 #1/#2) are scoped correctly at the SQL level.
 *
 * <p>Named {@code *IT} rather than {@code *Test} so Surefire (bound to {@code mvnw test}) skips it
 * and only Failsafe (bound to {@code mvnw verify}) runs it — see README.md for how to run it.
 */
@Testcontainers
class JdbcPromotionRepositoryIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcPromotionRepository repository;

	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("1.4.0");
	private final Actor requester = new Actor("alice", Role.REQUESTER);
	private final Actor approver = new Actor("bob", Role.APPROVER);

	@BeforeAll
	static void migrateSchemaAndConnect() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRES.getJdbcUrl());
		config.setUsername(POSTGRES.getUsername());
		config.setPassword(POSTGRES.getPassword());
		dataSource = new HikariDataSource(config);

		Flyway.configure().dataSource(dataSource).load().migrate();

		repository = new JdbcPromotionRepository(new JdbcTemplate(dataSource));
	}

	@AfterAll
	static void closeDataSource() {
		dataSource.close();
	}

	private Promotion requestedPromotion() {
		return Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of());
	}

	// --- save / findById round-trip ---

	@Test
	void savingAndReloadingANewlyRequestedPromotionPreservesAllFields() {
		Promotion promotion = requestedPromotion();

		repository.save(promotion);
		Promotion reloaded = repository.findById(promotion.id()).orElseThrow();

		assertThat(reloaded.id()).isEqualTo(promotion.id());
		assertThat(reloaded.applicationId()).isEqualTo(applicationId);
		assertThat(reloaded.version()).isEqualTo(version);
		assertThat(reloaded.fromEnvironment()).isEmpty();
		assertThat(reloaded.targetEnvironment()).isEqualTo(Environment.DEV);
		assertThat(reloaded.requestedBy()).isEqualTo(requester);
		assertThat(reloaded.status()).isEqualTo(PromotionStatus.REQUESTED);
		assertThat(reloaded.approvedBy()).isEmpty();
	}

	@Test
	void findByIdReturnsEmptyForAnUnknownId() {
		assertThat(repository.findById(PromotionId.random())).isEmpty();
	}

	@Test
	void theFullLifecycleSurvivesReloadBetweenEachTransition() {
		Promotion promotion = requestedPromotion();
		repository.save(promotion);

		Promotion afterApprove = repository.findById(promotion.id()).orElseThrow();
		afterApprove.approve(approver);
		repository.save(afterApprove);

		Promotion afterStart = repository.findById(promotion.id()).orElseThrow();
		afterStart.startDeployment(approver);
		repository.save(afterStart);

		Promotion afterComplete = repository.findById(promotion.id()).orElseThrow();
		afterComplete.complete(approver);
		repository.save(afterComplete);

		Promotion finalState = repository.findById(promotion.id()).orElseThrow();
		assertThat(finalState.status()).isEqualTo(PromotionStatus.COMPLETED);
		assertThat(finalState.approvedBy()).contains(approver);
	}

	// --- findActivePromotionsForTarget / invariant #2 ---

	@Test
	void findActivePromotionsForTargetExcludesTerminalPromotions() {
		Promotion cancelled = requestedPromotion();
		cancelled.cancel(requester);
		repository.save(cancelled);

		Promotion active = requestedPromotion();
		repository.save(active);

		List<Promotion> found = repository.findActivePromotionsForTarget(applicationId, Environment.DEV);

		assertThat(found).extracting(promotion -> promotion.id()).containsExactly(active.id());
	}

	@Test
	void findActivePromotionsForTargetIsScopedToTheGivenApplicationAndEnvironment() {
		Promotion sameAppDifferentEnvironment = Promotion.request(
				PromotionId.random(), applicationId, version, Environment.DEV, Environment.STAGING, requester,
				List.of());
		repository.save(sameAppDifferentEnvironment);

		Promotion differentApp = Promotion.request(
				PromotionId.random(), ApplicationId.random(), version, null, Environment.DEV, requester, List.of());
		repository.save(differentApp);

		assertThat(repository.findActivePromotionsForTarget(applicationId, Environment.DEV)).isEmpty();
	}

	// --- findLastCompletedEnvironment / invariant #1 ---

	@Test
	void findLastCompletedEnvironmentReturnsEmptyWhenNothingCompleted() {
		repository.save(requestedPromotion());

		assertThat(repository.findLastCompletedEnvironment(applicationId, version)).isEmpty();
	}

	@Test
	void findLastCompletedEnvironmentReturnsTheHighestCompletedEnvironment() {
		Promotion devPromotion = requestedPromotion();
		devPromotion.approve(approver);
		devPromotion.startDeployment(approver);
		devPromotion.complete(approver);
		repository.save(devPromotion);

		Promotion stagingPromotion = Promotion.request(
				PromotionId.random(), applicationId, version, Environment.DEV, Environment.STAGING, requester,
				List.of());
		stagingPromotion.approve(approver);
		stagingPromotion.startDeployment(approver);
		stagingPromotion.complete(approver);
		repository.save(stagingPromotion);

		assertThat(repository.findLastCompletedEnvironment(applicationId, version)).contains(Environment.STAGING);
	}

	// --- repository + aggregate integration, mirroring how the application layer drives both ---

	@Test
	void repositoryScopedQueriesLetTheAggregateEnforceInvariantOneAcrossReloads() {
		Promotion devPromotion = requestedPromotion();
		devPromotion.approve(approver);
		devPromotion.startDeployment(approver);
		devPromotion.complete(approver);
		repository.save(devPromotion);

		Environment lastCompleted =
				repository.findLastCompletedEnvironment(applicationId, version).orElseThrow();
		List<Promotion> existingForStaging =
				repository.findActivePromotionsForTarget(applicationId, Environment.STAGING);

		Promotion stagingPromotion = Promotion.request(
				PromotionId.random(), applicationId, version, lastCompleted, Environment.STAGING, requester,
				existingForStaging);

		assertThat(stagingPromotion.status()).isEqualTo(PromotionStatus.REQUESTED);
		assertThatThrownBy(() -> Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.PRODUCTION, requester, List.of()))
				.isInstanceOf(EnvironmentSkippedError.class);
	}

	@Test
	void repositoryScopedQueriesLetTheAggregateEnforceInvariantTwoAcrossReloads() {
		Promotion first = requestedPromotion();
		repository.save(first);

		List<Promotion> existingForTarget =
				repository.findActivePromotionsForTarget(applicationId, Environment.DEV);

		assertThatThrownBy(() -> Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, existingForTarget))
				.isInstanceOf(DuplicatePromotionInProgressError.class);
	}

	/**
	 * Simulates the race {@code uq_promotions_active_target} backstops: two concurrent
	 * {@code RequestPromotion} calls can both pass the application layer's check-then-act (each
	 * sees an empty {@code existingForTarget} list) before either has saved, so the aggregate
	 * itself never sees a conflict. The database must still refuse the second insert.
	 */
	@Test
	void savingASecondConcurrentlyRequestedPromotionForTheSameTargetViolatesTheUniqueIndex() {
		Promotion first = Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of());
		Promotion racingSecond = Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of());

		repository.save(first);

		assertThatThrownBy(() -> repository.save(racingSecond))
				.isInstanceOf(DuplicatePromotionInProgressError.class);
	}
}
