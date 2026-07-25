package com.releasepilot.infrastructure.persistence;

import com.releasepilot.domain.ports.PromotionRepositoryPort;
import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Role;
import com.releasepilot.domain.promotion.Version;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link PromotionRepositoryPort}, backed by the {@code promotions} table
 * (see {@code db/migration/V1__create_promotions_table.sql}). Maps rows to/from {@link Promotion}
 * explicitly with plain {@link JdbcTemplate} rather than a Spring Data JPA/JDBC entity, so the
 * aggregate's own constructors/factories stay in full control of its invariants — see SPECS.md §11.
 *
 * <p>Not yet registered as a Spring bean: nothing in the application context currently depends on
 * {@link PromotionRepositoryPort}, so wiring this in is deferred to when the command handlers are
 * connected to the HTTP layer. Until then it is exercised directly by its integration test.
 */
public class JdbcPromotionRepository implements PromotionRepositoryPort {

	private static final RowMapper<Promotion> ROW_MAPPER = JdbcPromotionRepository::mapRow;

	private final JdbcTemplate jdbcTemplate;

	public JdbcPromotionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void save(Promotion promotion) {
		jdbcTemplate.update(
				"""
				INSERT INTO promotions (
				    id, application_id, version, from_environment, target_environment,
				    requested_by_user_id, requested_by_role, status, approved_by_user_id, approved_by_role
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (id) DO UPDATE SET
				    status = EXCLUDED.status,
				    approved_by_user_id = EXCLUDED.approved_by_user_id,
				    approved_by_role = EXCLUDED.approved_by_role
				""",
				promotion.id().value(),
				promotion.applicationId().value(),
				promotion.version().value(),
				promotion.fromEnvironment().map(Enum::name).orElse(null),
				promotion.targetEnvironment().name(),
				promotion.requestedBy().userId(),
				promotion.requestedBy().role().name(),
				promotion.status().name(),
				promotion.approvedBy().map(Actor::userId).orElse(null),
				promotion.approvedBy().map(actor -> actor.role().name()).orElse(null));
	}

	@Override
	public Optional<Promotion> findById(PromotionId promotionId) {
		return jdbcTemplate.query("SELECT * FROM promotions WHERE id = ?", ROW_MAPPER, promotionId.value())
				.stream()
				.findFirst();
	}

	@Override
	public List<Promotion> findActivePromotionsForTarget(ApplicationId applicationId, Environment targetEnvironment) {
		return jdbcTemplate.query(
				"""
				SELECT * FROM promotions
				WHERE application_id = ? AND target_environment = ?
				  AND status NOT IN ('COMPLETED', 'ROLLED_BACK', 'CANCELLED')
				""",
				ROW_MAPPER,
				applicationId.value(),
				targetEnvironment.name());
	}

	@Override
	public Optional<Environment> findLastCompletedEnvironment(ApplicationId applicationId, Version version) {
		return jdbcTemplate.queryForList(
						"""
						SELECT target_environment FROM promotions
						WHERE application_id = ? AND version = ? AND status = ?
						""",
						String.class,
						applicationId.value(),
						version.value(),
						PromotionStatus.COMPLETED.name())
				.stream()
				.map(Environment::valueOf)
				.max(Comparator.comparingInt(Enum::ordinal));
	}

	private static Promotion mapRow(ResultSet rs, int rowNum) throws SQLException {
		String fromEnvironment = rs.getString("from_environment");
		String approvedByUserId = rs.getString("approved_by_user_id");
		Actor approvedBy = approvedByUserId == null
				? null
				: new Actor(approvedByUserId, Role.valueOf(rs.getString("approved_by_role")));

		return Promotion.reconstitute(
				new PromotionId(rs.getObject("id", UUID.class)),
				new ApplicationId(rs.getObject("application_id", UUID.class)),
				new Version(rs.getString("version")),
				fromEnvironment == null ? null : Environment.valueOf(fromEnvironment),
				Environment.valueOf(rs.getString("target_environment")),
				new Actor(rs.getString("requested_by_user_id"), Role.valueOf(rs.getString("requested_by_role"))),
				PromotionStatus.valueOf(rs.getString("status")),
				approvedBy);
	}
}
