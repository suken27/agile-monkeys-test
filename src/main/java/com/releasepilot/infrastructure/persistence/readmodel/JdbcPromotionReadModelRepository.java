package com.releasepilot.infrastructure.persistence.readmodel;

import com.releasepilot.application.queries.ApplicationEnvironmentStatus;
import com.releasepilot.application.queries.ApplicationEnvironmentStatus.EnvironmentStatus;
import com.releasepilot.application.queries.PromotionDetail;
import com.releasepilot.application.queries.PromotionDetail.PromotionHistoryEntry;
import com.releasepilot.application.queries.PromotionHistoryPage;
import com.releasepilot.application.queries.PromotionHistoryPage.PromotionHistoryItem;
import com.releasepilot.application.queries.PromotionReadModelPort;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JDBC implementation of {@link PromotionReadModelPort}, backed by the {@code promotion_detail},
 * {@code promotion_detail_history}, {@code application_environment_status} and
 * {@code promotion_history} tables (see {@code db/migration/V3__create_read_model_tables.sql}).
 * These tables are populated by the read-model projector consumer (SPECS §8) — a future
 * addition — so until that consumer exists, every query here legitimately returns "not found" /
 * empty results against a fresh database; that is a data-population gap, not a bug in this
 * adapter, which is exercised directly against seeded rows by its integration test.
 */
@Repository
public class JdbcPromotionReadModelRepository implements PromotionReadModelPort {

	private static final RowMapper<PromotionDetail> DETAIL_ROW_MAPPER =
			JdbcPromotionReadModelRepository::mapDetailRow;
	private static final RowMapper<PromotionHistoryEntry> HISTORY_ENTRY_ROW_MAPPER =
			JdbcPromotionReadModelRepository::mapHistoryEntryRow;
	private static final RowMapper<EnvironmentStatus> ENVIRONMENT_STATUS_ROW_MAPPER =
			JdbcPromotionReadModelRepository::mapEnvironmentStatusRow;
	private static final RowMapper<PromotionHistoryItem> HISTORY_ITEM_ROW_MAPPER =
			JdbcPromotionReadModelRepository::mapHistoryItemRow;

	private final JdbcTemplate jdbcTemplate;

	public JdbcPromotionReadModelRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<PromotionDetail> findPromotionDetail(PromotionId promotionId) {
		Optional<PromotionDetail> detail = jdbcTemplate.query(
						"SELECT * FROM promotion_detail WHERE id = ?", DETAIL_ROW_MAPPER, promotionId.value())
				.stream()
				.findFirst();
		if (detail.isEmpty()) {
			return Optional.empty();
		}

		List<PromotionHistoryEntry> history = jdbcTemplate.query(
				"""
				SELECT status, actor_user_id, occurred_at FROM promotion_detail_history
				WHERE promotion_id = ? ORDER BY occurred_at
				""",
				HISTORY_ENTRY_ROW_MAPPER,
				promotionId.value());

		return detail.map(d -> new PromotionDetail(
				d.id(), d.applicationId(), d.version(), d.fromEnvironment(), d.targetEnvironment(), d.status(),
				d.requestedBy(), d.approvedBy(), history));
	}

	@Override
	public ApplicationEnvironmentStatus findApplicationEnvironmentStatus(ApplicationId applicationId) {
		Map<Environment, EnvironmentStatus> byEnvironment = jdbcTemplate.query(
						"""
						SELECT environment, current_version, status, last_promotion_id
						FROM application_environment_status WHERE application_id = ?
						""",
						ENVIRONMENT_STATUS_ROW_MAPPER,
						applicationId.value())
				.stream()
				.collect(Collectors.toMap(status -> status.environment(), status -> status));

		List<EnvironmentStatus> environments = Arrays.stream(Environment.values())
				.map(env -> byEnvironment.getOrDefault(env, new EnvironmentStatus(env, null, null, null)))
				.toList();

		return new ApplicationEnvironmentStatus(applicationId, environments);
	}

	@Override
	public PromotionHistoryPage findApplicationPromotions(ApplicationId applicationId, int page, int pageSize) {
		long total = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM promotion_history WHERE application_id = ?", Long.class,
				applicationId.value());

		List<PromotionHistoryItem> items = jdbcTemplate.query(
				"""
				SELECT * FROM promotion_history WHERE application_id = ?
				ORDER BY requested_at DESC
				LIMIT ? OFFSET ?
				""",
				HISTORY_ITEM_ROW_MAPPER,
				applicationId.value(),
				pageSize,
				page * pageSize);

		return new PromotionHistoryPage(items, page, pageSize, total);
	}

	private static PromotionDetail mapDetailRow(ResultSet rs, int rowNum) throws SQLException {
		return new PromotionDetail(
				new PromotionId(rs.getObject("id", UUID.class)),
				new ApplicationId(rs.getObject("application_id", UUID.class)),
				new Version(rs.getString("version")),
				environmentOrNull(rs.getString("from_environment")),
				Environment.valueOf(rs.getString("target_environment")),
				PromotionStatus.valueOf(rs.getString("status")),
				rs.getString("requested_by_user_id"),
				rs.getString("approved_by_user_id"),
				List.of());
	}

	private static PromotionHistoryEntry mapHistoryEntryRow(ResultSet rs, int rowNum) throws SQLException {
		return new PromotionHistoryEntry(
				PromotionStatus.valueOf(rs.getString("status")),
				rs.getString("actor_user_id"),
				rs.getTimestamp("occurred_at").toInstant());
	}

	private static EnvironmentStatus mapEnvironmentStatusRow(ResultSet rs, int rowNum) throws SQLException {
		String currentVersion = rs.getString("current_version");
		String status = rs.getString("status");
		UUID lastPromotionId = rs.getObject("last_promotion_id", UUID.class);
		return new EnvironmentStatus(
				Environment.valueOf(rs.getString("environment")),
				currentVersion == null ? null : new Version(currentVersion),
				status == null ? null : PromotionStatus.valueOf(status),
				lastPromotionId == null ? null : new PromotionId(lastPromotionId));
	}

	private static PromotionHistoryItem mapHistoryItemRow(ResultSet rs, int rowNum) throws SQLException {
		var completedAt = rs.getTimestamp("completed_at");
		return new PromotionHistoryItem(
				new PromotionId(rs.getObject("id", UUID.class)),
				new Version(rs.getString("version")),
				environmentOrNull(rs.getString("from_environment")),
				Environment.valueOf(rs.getString("target_environment")),
				PromotionStatus.valueOf(rs.getString("status")),
				rs.getTimestamp("requested_at").toInstant(),
				completedAt == null ? null : completedAt.toInstant());
	}

	private static Environment environmentOrNull(String value) {
		return value == null ? null : Environment.valueOf(value);
	}
}
