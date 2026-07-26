package com.releasepilot.consumers;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Read-model projector consumer (SPECS §8.2): consumes every {@link DomainEvent} published off the
 * outbox and updates {@code promotion_detail}, {@code promotion_detail_history},
 * {@code application_environment_status} and {@code promotion_history} (SPECS §7) — the query side
 * never reads the write-side {@code promotions} table directly.
 *
 * <p>Only {@code PromotionRequested}'s payload carries the promotion's {@code version} and {@code
 * targetEnvironment} (SPECS §5) — every later event for the same promotion looks those up from the
 * {@code promotion_detail} row that event created, rather than requiring every event to repeat them.
 * This assumes events for a given promotion are delivered in the order they were recorded, which
 * holds here because a single fanout-bound queue preserves publish order and the aggregate itself
 * only ever produces one promotion's events in that same order (SPECS §5.1).
 *
 * <p>Deliberately not a {@code @RabbitListener} bean itself — like {@code OutboxRelay}, this class
 * depends on nothing but a {@link DomainEvent} and a {@link JdbcTemplate}, which keeps it directly
 * testable (SPECS §14.3) without a broker; the queue transport that decodes a message into a {@code
 * DomainEvent} and calls {@link #project} is a separate, thin adapter.
 */
public class ReadModelProjector {

	private final JdbcTemplate jdbcTemplate;

	public ReadModelProjector(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void project(DomainEvent event) {
		switch (event.eventType()) {
			case Promotion.EVENT_PROMOTION_REQUESTED -> onPromotionRequested(event);
			case Promotion.EVENT_PROMOTION_APPROVED -> onPromotionApproved(event);
			case Promotion.EVENT_DEPLOYMENT_STARTED -> transitionTo(event, PromotionStatus.IN_PROGRESS, false);
			case Promotion.EVENT_PROMOTION_COMPLETED -> transitionTo(event, PromotionStatus.COMPLETED, true);
			case Promotion.EVENT_PROMOTION_ROLLED_BACK -> transitionTo(event, PromotionStatus.ROLLED_BACK, true);
			case Promotion.EVENT_PROMOTION_CANCELLED -> transitionTo(event, PromotionStatus.CANCELLED, true);
			default -> throw new IllegalArgumentException("Unknown event type: " + event.eventType());
		}
	}

	private void onPromotionRequested(DomainEvent event) {
		String version = (String) event.payload().get("version");
		String fromEnvironment = (String) event.payload().get("fromEnvironment");
		String targetEnvironment = (String) event.payload().get("targetEnvironment");

		jdbcTemplate.update(
				"""
				INSERT INTO promotion_detail
				    (id, application_id, version, from_environment, target_environment, status,
				     requested_by_user_id, approved_by_user_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
				ON CONFLICT (id) DO NOTHING
				""",
				event.promotionId().value(), event.applicationId().value(), version, fromEnvironment,
				targetEnvironment, PromotionStatus.REQUESTED.name(), event.actingUser());

		jdbcTemplate.update(
				"""
				INSERT INTO promotion_history
				    (id, application_id, version, from_environment, target_environment, status, requested_at,
				     completed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
				ON CONFLICT (id) DO NOTHING
				""",
				event.promotionId().value(), event.applicationId().value(), version, fromEnvironment,
				targetEnvironment, PromotionStatus.REQUESTED.name(), Timestamp.from(event.occurredAt()));

		appendHistory(event, PromotionStatus.REQUESTED);
		upsertEnvironmentStatus(
				event.applicationId(), Environment.valueOf(targetEnvironment), event.promotionId(),
				PromotionStatus.REQUESTED, null);
	}

	private void onPromotionApproved(DomainEvent event) {
		transitionTo(event, PromotionStatus.APPROVED, false);
		jdbcTemplate.update(
				"UPDATE promotion_detail SET approved_by_user_id = ? WHERE id = ?",
				(String) event.payload().get("approvedBy"), event.promotionId().value());
	}

	/**
	 * Shared tail for every event after {@code PromotionRequested}: updates {@code promotion_detail}
	 * and {@code promotion_history}'s status (setting {@code completed_at} once {@code terminal}),
	 * appends the history row, and refreshes the promotion's target environment's status row. Only
	 * on {@code PromotionCompleted} does the environment's {@code current_version} actually advance
	 * — every other transition (including a rollback) leaves it pointing at whatever last completed
	 * there, since the version never successfully landed.
	 */
	private void transitionTo(DomainEvent event, PromotionStatus newStatus, boolean terminal) {
		jdbcTemplate.update(
				"UPDATE promotion_detail SET status = ? WHERE id = ?",
				newStatus.name(), event.promotionId().value());

		if (terminal) {
			jdbcTemplate.update(
					"UPDATE promotion_history SET status = ?, completed_at = ? WHERE id = ?",
					newStatus.name(), Timestamp.from(event.occurredAt()), event.promotionId().value());
		} else {
			jdbcTemplate.update(
					"UPDATE promotion_history SET status = ? WHERE id = ?",
					newStatus.name(), event.promotionId().value());
		}

		appendHistory(event, newStatus);

		PromotionRef ref = findPromotionRef(event.promotionId());
		Version deployedVersion = newStatus == PromotionStatus.COMPLETED ? ref.version() : null;
		upsertEnvironmentStatus(
				ref.applicationId(), ref.targetEnvironment(), event.promotionId(), newStatus, deployedVersion);
	}

	private void appendHistory(DomainEvent event, PromotionStatus status) {
		jdbcTemplate.update(
				"""
				INSERT INTO promotion_detail_history (promotion_id, status, actor_user_id, occurred_at)
				VALUES (?, ?, ?, ?)
				""",
				event.promotionId().value(), status.name(), event.actingUser(), Timestamp.from(event.occurredAt()));
	}

	/**
	 * Upserts the (application, environment) row. {@code deployedVersion} of {@code null} leaves
	 * {@code current_version} unchanged rather than clearing it — passed only when {@code status} is
	 * {@code COMPLETED}.
	 */
	private void upsertEnvironmentStatus(
			ApplicationId applicationId, Environment environment, PromotionId promotionId, PromotionStatus status,
			Version deployedVersion) {
		jdbcTemplate.update(
				"""
				INSERT INTO application_environment_status
				    (application_id, environment, current_version, status, last_promotion_id)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (application_id, environment) DO UPDATE
				SET status = EXCLUDED.status,
				    last_promotion_id = EXCLUDED.last_promotion_id,
				    current_version = COALESCE(EXCLUDED.current_version, application_environment_status.current_version)
				""",
				applicationId.value(), environment.name(), deployedVersion == null ? null : deployedVersion.value(),
				status.name(), promotionId.value());
	}

	private PromotionRef findPromotionRef(PromotionId promotionId) {
		return jdbcTemplate.queryForObject(
				"SELECT application_id, version, target_environment FROM promotion_detail WHERE id = ?",
				(rs, rowNum) -> new PromotionRef(
						new ApplicationId(rs.getObject("application_id", UUID.class)),
						new Version(rs.getString("version")),
						Environment.valueOf(rs.getString("target_environment"))),
				promotionId.value());
	}

	private record PromotionRef(ApplicationId applicationId, Version version, Environment targetEnvironment) {
	}
}
