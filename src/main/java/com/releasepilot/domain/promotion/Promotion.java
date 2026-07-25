package com.releasepilot.domain.promotion;

import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
import com.releasepilot.domain.promotion.errors.InvalidTransitionError;
import com.releasepilot.domain.promotion.errors.PromotionAlreadyTerminalError;
import com.releasepilot.domain.promotion.errors.UnauthorizedApproverError;

import java.util.List;
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

	private final PromotionId id;
	private final ApplicationId applicationId;
	private final Version version;
	private final Environment fromEnvironment;
	private final Environment targetEnvironment;
	private final Actor requestedBy;
	private PromotionStatus status;
	private Actor approvedBy;

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

		return new Promotion(
				id, applicationId, version, lastCompletedEnvironment, targetEnvironment, requestedBy,
				PromotionStatus.REQUESTED);
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
	}

	public void startDeployment(Actor actor) {
		requireNotTerminal();
		if (status != PromotionStatus.APPROVED) {
			throw new InvalidTransitionError(status, "StartDeployment");
		}
		this.status = PromotionStatus.IN_PROGRESS;
	}

	public void complete(Actor actor) {
		requireNotTerminal();
		if (status != PromotionStatus.IN_PROGRESS) {
			throw new InvalidTransitionError(status, "CompletePromotion");
		}
		this.status = PromotionStatus.COMPLETED;
	}

	public void rollback(Actor actor) {
		requireNotTerminal();
		if (status != PromotionStatus.IN_PROGRESS) {
			throw new InvalidTransitionError(status, "RollbackPromotion");
		}
		this.status = PromotionStatus.ROLLED_BACK;
	}

	/** Invariant #4/#5: cancellation is only possible before deployment has started. */
	public void cancel(Actor actor) {
		requireNotTerminal();
		if (status != PromotionStatus.REQUESTED && status != PromotionStatus.APPROVED) {
			throw new InvalidTransitionError(status, "CancelPromotion");
		}
		this.status = PromotionStatus.CANCELLED;
	}

	private void requireNotTerminal() {
		if (status.isTerminal()) {
			throw new PromotionAlreadyTerminalError(status);
		}
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
