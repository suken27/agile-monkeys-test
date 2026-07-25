package com.releasepilot.domain.promotion;

import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
import com.releasepilot.domain.promotion.errors.InvalidTransitionError;
import com.releasepilot.domain.promotion.errors.PromotionAlreadyTerminalError;
import com.releasepilot.domain.promotion.errors.UnauthorizedApproverError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionTest {

	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("1.4.0");
	private final Actor requester = new Actor("alice", Role.REQUESTER);
	private final Actor approver = new Actor("bob", Role.APPROVER);

	private Promotion requestedPromotion() {
		return Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of());
	}

	private Promotion approvedPromotion() {
		Promotion promotion = requestedPromotion();
		promotion.approve(approver);
		return promotion;
	}

	private Promotion inProgressPromotion() {
		Promotion promotion = approvedPromotion();
		promotion.startDeployment(approver);
		return promotion;
	}

	// --- RequestPromotion / invariant #1 (ordered pipeline, no skipping) ---

	@Test
	void requestingFirstHopIntoDevSucceedsWhenNothingCompletedYet() {
		Promotion promotion = Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of());

		assertThat(promotion.status()).isEqualTo(PromotionStatus.REQUESTED);
		assertThat(promotion.fromEnvironment()).isEmpty();
		assertThat(promotion.targetEnvironment()).isEqualTo(Environment.DEV);
		assertThat(promotion.requestedBy()).isEqualTo(requester);
	}

	@Test
	void requestingTheImmediateNextEnvironmentSucceeds() {
		Promotion promotion = Promotion.request(
				PromotionId.random(), applicationId, version, Environment.DEV, Environment.STAGING, requester,
				List.of());

		assertThat(promotion.status()).isEqualTo(PromotionStatus.REQUESTED);
		assertThat(promotion.fromEnvironment()).contains(Environment.DEV);
		assertThat(promotion.targetEnvironment()).isEqualTo(Environment.STAGING);
	}

	@Test
	void requestingProductionBeforeStagingIsCompletedIsRejected() {
		assertThatThrownBy(() -> Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.PRODUCTION, requester, List.of()))
				.isInstanceOf(EnvironmentSkippedError.class);
	}

	@Test
	void requestingBeyondProductionIsRejected() {
		assertThatThrownBy(() -> Promotion.request(
				PromotionId.random(), applicationId, version, Environment.PRODUCTION, Environment.PRODUCTION,
				requester, List.of()))
				.isInstanceOf(EnvironmentSkippedError.class);
	}

	// --- RequestPromotion / invariant #2 (one in-flight promotion per application+target) ---

	@Test
	void requestingWhileAnotherNonTerminalPromotionTargetsTheSameEnvironmentIsRejected() {
		Promotion existing = requestedPromotion();

		assertThatThrownBy(() -> Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of(existing)))
				.isInstanceOf(DuplicatePromotionInProgressError.class);
	}

	@Test
	void requestingIsAllowedWhenAllPriorPromotionsForTheTargetAreTerminal() {
		Promotion cancelled = requestedPromotion();
		cancelled.cancel(requester);

		Promotion promotion = Promotion.request(
				PromotionId.random(), applicationId, version, null, Environment.DEV, requester, List.of(cancelled));

		assertThat(promotion.status()).isEqualTo(PromotionStatus.REQUESTED);
	}

	// --- ApprovePromotion / invariant #3 (only an approver may approve) ---

	@Test
	void approvingWithTheApproverRoleTransitionsToApproved() {
		Promotion promotion = requestedPromotion();

		promotion.approve(approver);

		assertThat(promotion.status()).isEqualTo(PromotionStatus.APPROVED);
		assertThat(promotion.approvedBy()).contains(approver);
	}

	@Test
	void approvingWithoutTheApproverRoleIsRejected() {
		Promotion promotion = requestedPromotion();

		assertThatThrownBy(() -> promotion.approve(requester))
				.isInstanceOf(UnauthorizedApproverError.class);
		assertThat(promotion.status()).isEqualTo(PromotionStatus.REQUESTED);
	}

	@Test
	void approvingAPromotionThatIsNotRequestedIsRejected() {
		Promotion promotion = approvedPromotion();

		assertThatThrownBy(() -> promotion.approve(approver))
				.isInstanceOf(InvalidTransitionError.class);
	}

	// --- StartDeployment ---

	@Test
	void startingDeploymentFromApprovedTransitionsToInProgress() {
		Promotion promotion = approvedPromotion();

		promotion.startDeployment(approver);

		assertThat(promotion.status()).isEqualTo(PromotionStatus.IN_PROGRESS);
	}

	@Test
	void startingDeploymentBeforeApprovalIsRejected() {
		Promotion promotion = requestedPromotion();

		assertThatThrownBy(() -> promotion.startDeployment(approver))
				.isInstanceOf(InvalidTransitionError.class);
	}

	// --- CompletePromotion / RollbackPromotion ---

	@Test
	void completingAnInProgressPromotionTransitionsToCompleted() {
		Promotion promotion = inProgressPromotion();

		promotion.complete(approver);

		assertThat(promotion.status()).isEqualTo(PromotionStatus.COMPLETED);
	}

	@Test
	void completingAPromotionThatHasNotStartedIsRejected() {
		Promotion promotion = approvedPromotion();

		assertThatThrownBy(() -> promotion.complete(approver))
				.isInstanceOf(InvalidTransitionError.class);
	}

	@Test
	void rollingBackAnInProgressPromotionTransitionsToRolledBack() {
		Promotion promotion = inProgressPromotion();

		promotion.rollback(approver);

		assertThat(promotion.status()).isEqualTo(PromotionStatus.ROLLED_BACK);
	}

	@Test
	void rollingBackAPromotionThatHasNotStartedIsRejected() {
		Promotion promotion = approvedPromotion();

		assertThatThrownBy(() -> promotion.rollback(approver))
				.isInstanceOf(InvalidTransitionError.class);
	}

	// --- CancelPromotion ---

	@Test
	void cancellingARequestedPromotionTransitionsToCancelled() {
		Promotion promotion = requestedPromotion();

		promotion.cancel(requester);

		assertThat(promotion.status()).isEqualTo(PromotionStatus.CANCELLED);
	}

	@Test
	void cancellingAnApprovedPromotionTransitionsToCancelled() {
		Promotion promotion = approvedPromotion();

		promotion.cancel(requester);

		assertThat(promotion.status()).isEqualTo(PromotionStatus.CANCELLED);
	}

	@Test
	void cancellingAPromotionAlreadyInProgressIsRejected() {
		Promotion promotion = inProgressPromotion();

		assertThatThrownBy(() -> promotion.cancel(requester))
				.isInstanceOf(InvalidTransitionError.class);
	}

	// --- Invariant #4 (immutability after terminal state) ---

	@Test
	void everyCommandAgainstACompletedPromotionIsRejected() {
		Promotion promotion = inProgressPromotion();
		promotion.complete(approver);

		assertThatThrownBy(() -> promotion.approve(approver)).isInstanceOf(PromotionAlreadyTerminalError.class);
		assertThatThrownBy(() -> promotion.startDeployment(approver)).isInstanceOf(PromotionAlreadyTerminalError.class);
		assertThatThrownBy(() -> promotion.complete(approver)).isInstanceOf(PromotionAlreadyTerminalError.class);
		assertThatThrownBy(() -> promotion.rollback(approver)).isInstanceOf(PromotionAlreadyTerminalError.class);
		assertThatThrownBy(() -> promotion.cancel(approver)).isInstanceOf(PromotionAlreadyTerminalError.class);
		assertThat(promotion.status()).isEqualTo(PromotionStatus.COMPLETED);
	}

	@Test
	void everyCommandAgainstACancelledPromotionIsRejected() {
		Promotion promotion = requestedPromotion();
		promotion.cancel(requester);

		assertThatThrownBy(() -> promotion.approve(approver)).isInstanceOf(PromotionAlreadyTerminalError.class);
		assertThat(promotion.status()).isEqualTo(PromotionStatus.CANCELLED);
	}
}
