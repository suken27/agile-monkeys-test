package com.releasepilot.domain.promotion;

import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
import com.releasepilot.domain.promotion.errors.InvalidTransitionError;
import com.releasepilot.domain.promotion.errors.PromotionAlreadyTerminalError;
import com.releasepilot.domain.promotion.errors.UnauthorizedApproverError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root. A {@code Promotion} is scoped to one application-version's one hop between
 * two adjacent pipeline environments, and guards every business rule around that hop itself —
 * no repository, handler, or API layer can bypass an invariant enforced here.
 *
 * <p>Invariants #1 (no skipping) and #2 (no duplicate in-flight promotion for the same target)
 * span multiple {@code Promotion} instances of the same application, so the data fetching for
 * those checks is the application layer's job (loading sibling promotions), but the decision
 * itself is made here, in {@link #request}.
 */
public final class Promotion {

	public static final String EVENT_PROMOTION_REQUESTED = "PromotionRequested";
	public static final String EVENT_PROMOTION_APPROVED = "PromotionApproved";
	public static final String EVENT_DEPLOYMENT_STARTED = "DeploymentStarted";
	public static final String EVENT_PROMOTION_COMPLETED = "PromotionCompleted";
	public static final String EVENT_PROMOTION_ROLLED_BACK = "PromotionRolledBack";
	public static final String EVENT_PROMOTION_CANCELLED = "PromotionCancelled";

	private final PromotionId id;
	private final ApplicationId applicationId;
	private final Version version;
	private final Environment fromEnvironment;
	private final Environment targetEnvironment;
	private final Actor requestedBy;
	private PromotionStatus status;
	private Actor approvedBy;

	/**
	 * Domain events recorded for transitions applied to this instance since the last
	 * {@link #pullDomainEvents()} call. Never persisted and never populated by {@link #reconstitute},
	 * since rehydrating a promotion from storage is not a new transition.
	 */
	private final List<DomainEvent> pendingEvents = new ArrayList<>();

	private Promotion(
			PromotionId id,
			ApplicationId applicationId,
			Version version,
			Environment fromEnvironment,
			Environment targetEnvironment,
			Actor requestedBy,
			PromotionStatus status) {
		this.id = Objects.requireNonNull(id);
		this.applicationId = Objects.requireNonNull(applicationId);
		this.version = Objects.requireNonNull(version);
		this.fromEnvironment = fromEnvironment;
		this.targetEnvironment = Objects.requireNonNull(targetEnvironment);
		this.requestedBy = Objects.requireNonNull(requestedBy);
		this.status = Objects.requireNonNull(status);
	}

	/**
	 * Creates a new promotion request for one step forward in the pipeline.
	 *
	 * @param lastCompletedEnvironment the version's last completed environment, or {@code null}
	 *                                 if the version has not completed any environment yet
	 * @param targetEnvironment        the environment this promotion would advance the version into
	 * @param existingPromotionsForTarget the active promotions for this (applicationId,
	 *                                 targetEnvironment) pair, fetched by the application layer;
	 *                                 used to enforce invariant #2. The adapter is expected to
	 *                                 scope this to non-terminal promotions, but this method
	 *                                 re-checks status itself rather than trusting that scoping.
	 */
	public static Promotion request(
			PromotionId id,
			ApplicationId applicationId,
			Version version,
			Environment lastCompletedEnvironment,
			Environment targetEnvironment,
			Actor requestedBy,
			List<Promotion> existingPromotionsForTarget) {
		Environment expectedTarget = lastCompletedEnvironment == null
				? Environment.first()
				: lastCompletedEnvironment.next().orElse(null);
		if (expectedTarget == null) {
			throw new EnvironmentSkippedError(targetEnvironment);
		}
		if (expectedTarget != targetEnvironment) {
			throw new EnvironmentSkippedError(expectedTarget, targetEnvironment);
		}

		boolean conflict = existingPromotionsForTarget.stream().anyMatch(p -> !p.status.isTerminal());
		if (conflict) {
			throw new DuplicatePromotionInProgressError(applicationId, targetEnvironment);
		}

		Promotion promotion = new Promotion(
				id, applicationId, version, lastCompletedEnvironment, targetEnvironment, requestedBy,
				PromotionStatus.REQUESTED);

		Map<String, Object> payload = lastCompletedEnvironment == null
				? Map.of("version", version.value(), "targetEnvironment", targetEnvironment.name())
				: Map.of(
						"version", version.value(),
						"fromEnvironment", lastCompletedEnvironment.name(),
						"targetEnvironment", targetEnvironment.name());
		promotion.recordEvent(EVENT_PROMOTION_REQUESTED, requestedBy, payload);

		return promotion;
	}

	/**
	 * Rehydrates a {@code Promotion} from persisted state. Unlike {@link #request}, this does not
	 * re-run invariants #1/#2 — a persisted row already satisfied them when it was first written,
	 * and re-checking here would require the adapter to pass in sibling promotions on every load,
	 * not just on creation. Persistence adapters call this; nothing else should need to.
	 */
	public static Promotion reconstitute(
			PromotionId id,
			ApplicationId applicationId,
			Version version,
			Environment fromEnvironment,
			Environment targetEnvironment,
			Actor requestedBy,
			PromotionStatus status,
			Actor approvedBy) {
		Promotion promotion =
				new Promotion(id, applicationId, version, fromEnvironment, targetEnvironment, requestedBy, status);
		promotion.approvedBy = approvedBy;
		return promotion;
	}

	/** Invariant #3: only an actor with the approver role may approve. */
	public void approve(Actor approver) {
		requireNotTerminal();
		if (status != PromotionStatus.REQUESTED) {
			throw new InvalidTransitionError(status, "ApprovePromotion");
		}
		if (!approver.isApprover()) {
			throw new UnauthorizedApproverError(approver.userId());
		}
		this.status = PromotionStatus.APPROVED;
		this.approvedBy = approver;
		recordEvent(EVENT_PROMOTION_APPROVED, approver, Map.of("approvedBy", approver.userId()));
	}

	/**
	 * The {@code deploymentRef} extra payload field (SPECS §5) is not known here — it comes back
	 * from {@link com.releasepilot.domain.ports.DeploymentPort#trigger} after this transition
	 * succeeds — so the recorded event carries an empty payload and the command handler enriches
	 * it via {@link DomainEvent#withPayload} once the deployment has actually been triggered.
	 */
	public void startDeployment(Actor actor) {
		requireNotTerminal();
		if (status != PromotionStatus.APPROVED) {
			throw new InvalidTransitionError(status, "StartDeployment");
		}
		this.status = PromotionStatus.IN_PROGRESS;
		recordEvent(EVENT_DEPLOYMENT_STARTED, actor, Map.of());
	}

	public void complete(Actor actor) {
		requireNotTerminal();
		if (status != PromotionStatus.IN_PROGRESS) {
			throw new InvalidTransitionError(status, "CompletePromotion");
		}
		this.status = PromotionStatus.COMPLETED;
		recordEvent(EVENT_PROMOTION_COMPLETED, actor, Map.of());
	}

	public void rollback(Actor actor) {
		rollback(actor, null);
	}

	/** @param reason optional free-text reason (SPECS §4/§5); omitted from the event payload when {@code null}. */
	public void rollback(Actor actor, String reason) {
		requireNotTerminal();
		if (status != PromotionStatus.IN_PROGRESS) {
			throw new InvalidTransitionError(status, "RollbackPromotion");
		}
		this.status = PromotionStatus.ROLLED_BACK;
		recordEvent(EVENT_PROMOTION_ROLLED_BACK, actor, reasonPayload(reason));
	}

	/** Invariant #4/#5: cancellation is only possible before deployment has started. */
	public void cancel(Actor actor) {
		cancel(actor, null);
	}

	/** @param reason optional free-text reason (SPECS §4/§5); omitted from the event payload when {@code null}. */
	public void cancel(Actor actor, String reason) {
		requireNotTerminal();
		if (status != PromotionStatus.REQUESTED && status != PromotionStatus.APPROVED) {
			throw new InvalidTransitionError(status, "CancelPromotion");
		}
		this.status = PromotionStatus.CANCELLED;
		recordEvent(EVENT_PROMOTION_CANCELLED, actor, reasonPayload(reason));
	}

	private static Map<String, Object> reasonPayload(String reason) {
		return reason == null ? Map.of() : Map.of("reason", reason);
	}

	private void requireNotTerminal() {
		if (status.isTerminal()) {
			throw new PromotionAlreadyTerminalError(status);
		}
	}

	private void recordEvent(String eventType, Actor actingActor, Map<String, Object> payload) {
		pendingEvents.add(DomainEvent.of(eventType, id, applicationId, actingActor, payload));
	}

	/**
	 * Returns the domain events recorded by transitions applied since the last call, then clears
	 * them. The application layer calls this right after persisting the aggregate's new state, and
	 * publishes what it returns through {@code EventPublisherPort} — the transactional-outbox
	 * adapter writes the event row in the same DB transaction as the state write (SPECS §5.1).
	 */
	public List<DomainEvent> pullDomainEvents() {
		List<DomainEvent> events = List.copyOf(pendingEvents);
		pendingEvents.clear();
		return events;
	}

	public PromotionId id() {
		return id;
	}

	public ApplicationId applicationId() {
		return applicationId;
	}

	public Version version() {
		return version;
	}

	public Optional<Environment> fromEnvironment() {
		return Optional.ofNullable(fromEnvironment);
	}

	public Environment targetEnvironment() {
		return targetEnvironment;
	}

	public PromotionStatus status() {
		return status;
	}

	public Actor requestedBy() {
		return requestedBy;
	}

	public Optional<Actor> approvedBy() {
		return Optional.ofNullable(approvedBy);
	}
}
