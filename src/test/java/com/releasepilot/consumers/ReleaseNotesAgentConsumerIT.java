package com.releasepilot.consumers;

import com.releasepilot.domain.ports.WorkItem;
import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Role;
import com.releasepilot.domain.promotion.Version;
import com.releasepilot.infrastructure.adapters.inmemory.InMemoryIssueTrackerAdapter;
import com.releasepilot.infrastructure.adapters.inmemory.MockedReleaseNotesLlmAdapter;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ReleaseNotesAgentConsumer} against a real PostgreSQL instance (via
 * Testcontainers), proving the tool-calling loop genuinely round-trips through
 * {@link MockedReleaseNotesLlmAdapter}, {@link InMemoryIssueTrackerAdapter} and
 * {@link JdbcPromotionReadModelRepository} and lands a drafted row in {@code release_notes} (SPECS
 * §9). This is the "consumer test" from SPECS §14.3: feed a {@code DomainEvent} in, assert the
 * persisted result is correct.
 *
 * <p>Named {@code *IT} rather than {@code *Test} — see {@code JdbcPromotionRepositoryIT} for why
 * only {@code mvnw verify} runs it.
 */
@Testcontainers
class ReleaseNotesAgentConsumerIT {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;
	private static ReadModelProjector projector;
	private static InMemoryIssueTrackerAdapter issueTracker;
	private static ReleaseNotesAgentConsumer consumer;

	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("2.3.0");
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
		issueTracker = new InMemoryIssueTrackerAdapter();
		consumer = new ReleaseNotesAgentConsumer(
				new MockedReleaseNotesLlmAdapter(), issueTracker, new JdbcPromotionReadModelRepository(jdbcTemplate),
				jdbcTemplate);
	}

	@AfterAll
	static void closeDataSource() {
		dataSource.close();
	}

	@BeforeEach
	void cleanTables() {
		jdbcTemplate.update("DELETE FROM release_notes");
		jdbcTemplate.update("DELETE FROM promotion_detail_history");
		jdbcTemplate.update("DELETE FROM promotion_history");
		jdbcTemplate.update("DELETE FROM application_environment_status");
		jdbcTemplate.update("DELETE FROM promotion_detail");
	}

	private PromotionId requestAndApprove(PromotionId promotionId) {
		projector.project(DomainEvent.of(
				Promotion.EVENT_PROMOTION_REQUESTED, promotionId, applicationId, alice,
				Map.of("version", version.value(), "fromEnvironment", "DEV", "targetEnvironment", "STAGING")));
		return promotionId;
	}

	@Test
	void approvedPromotionWithLinkedWorkItemsDraftsAndSavesReleaseNotes() {
		PromotionId promotionId = requestAndApprove(PromotionId.random());
		issueTracker.seed(applicationId, version, List.of(
				new WorkItem(
						"TICKET-1", "Fix login bug", "https://issues.example.com/TICKET-1",
						"Users could not log in with expired SSO tokens.")));

		consumer.onEvent(DomainEvent.of(
				Promotion.EVENT_PROMOTION_APPROVED, promotionId, applicationId, bob, Map.of("approvedBy", "bob")));

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT * FROM release_notes WHERE promotion_id = ?", promotionId.value());
		assertThat(row.get("application_id")).isEqualTo(applicationId.value());
		assertThat(row.get("version")).isEqualTo(version.value());
		assertThat((String) row.get("draft_text")).contains("TICKET-1", "Fix login bug");
	}

	@Test
	void approvedPromotionAsksClarificationAndFlagsBreakingChangesBeforeDrafting() {
		PromotionId promotionId = requestAndApprove(PromotionId.random());
		issueTracker.seed(applicationId, version, List.of(
				new WorkItem("TICKET-1", "Improve caching", "https://issues.example.com/TICKET-1", null),
				new WorkItem(
						"TICKET-2", "Remove legacy auth endpoint", "https://issues.example.com/TICKET-2",
						"This is a breaking change for API v1 clients.")));
		issueTracker.seedClarificationAnswer("TICKET-1", "Only affects internal cache invalidation.");

		consumer.onEvent(DomainEvent.of(
				Promotion.EVENT_PROMOTION_APPROVED, promotionId, applicationId, bob, Map.of("approvedBy", "bob")));

		String draftText = jdbcTemplate.queryForObject(
				"SELECT draft_text FROM release_notes WHERE promotion_id = ?", String.class, promotionId.value());
		assertThat(draftText).contains(
				"TICKET-1", "Only affects internal cache invalidation.",
				"TICKET-2", "breaking change");
	}

	@Test
	void approvedPromotionWithNoLinkedWorkItemsStillDraftsReleaseNotes() {
		PromotionId promotionId = requestAndApprove(PromotionId.random());

		consumer.onEvent(DomainEvent.of(
				Promotion.EVENT_PROMOTION_APPROVED, promotionId, applicationId, bob, Map.of("approvedBy", "bob")));

		String draftText = jdbcTemplate.queryForObject(
				"SELECT draft_text FROM release_notes WHERE promotion_id = ?", String.class, promotionId.value());
		assertThat(draftText).contains("No linked work items found");
	}

	@Test
	void nonApprovedEventsAreIgnored() {
		PromotionId promotionId = requestAndApprove(PromotionId.random());

		consumer.onEvent(DomainEvent.of(
				Promotion.EVENT_PROMOTION_REQUESTED, promotionId, applicationId, alice,
				Map.of("version", version.value(), "fromEnvironment", "DEV", "targetEnvironment", "STAGING")));

		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM release_notes", Integer.class);
		assertThat(count).isZero();
	}

	@Test
	void redeliveringTheSameApprovedEventUpsertsRatherThanDuplicating() {
		PromotionId promotionId = requestAndApprove(PromotionId.random());
		DomainEvent approved = DomainEvent.of(
				Promotion.EVENT_PROMOTION_APPROVED, promotionId, applicationId, bob, Map.of("approvedBy", "bob"));

		consumer.onEvent(approved);
		consumer.onEvent(approved);

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM release_notes WHERE promotion_id = ?", Integer.class, promotionId.value());
		assertThat(count).isEqualTo(1);
	}
}
