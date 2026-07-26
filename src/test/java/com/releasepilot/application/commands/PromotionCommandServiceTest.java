package com.releasepilot.application.commands;

import com.releasepilot.domain.ports.DeploymentPort;
import com.releasepilot.domain.ports.DeploymentRef;
import com.releasepilot.domain.ports.EventPublisherPort;
import com.releasepilot.domain.ports.PromotionRepositoryPort;
import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Role;
import com.releasepilot.domain.promotion.Version;
import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
import com.releasepilot.domain.promotion.errors.UnauthorizedApproverError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCommandServiceTest {

	@Mock
	private DeploymentPort deploymentPort;

	@Mock
	private PromotionRepositoryPort repository;

	@Mock
	private EventPublisherPort eventPublisher;

	private PromotionCommandService service;

	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("1.4.0");
	private final Actor requester = new Actor("alice", Role.REQUESTER);
	private final Actor approver = new Actor("bob", Role.APPROVER);

	@BeforeEach
	void setUp() {
		service = new PromotionCommandService(deploymentPort, repository, eventPublisher);
	}

	@Test
	void requestPromotionThenApproveThenStartThenCompleteWalksTheHappyPath() {
		when(deploymentPort.trigger(any(), any(), any())).thenReturn(new DeploymentRef("deploy-1"));

		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));

		service.approvePromotion(id, approver);
		service.startDeployment(id, approver);
		service.completePromotion(id, approver);

		verify(deploymentPort).trigger(applicationId, version, Environment.DEV);
	}

	@Test
	void requestingProductionBeforeStagingIsCompletedIsRejectedThroughThePort() {
		assertThatThrownBy(() -> service.requestPromotion(applicationId, version, Environment.PRODUCTION, requester))
				.isInstanceOf(EnvironmentSkippedError.class);
	}

	@Test
	void requestingTheSameTargetTwiceWhileTheFirstIsStillInFlightIsRejected() {
		service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion first = savedPromotion();
		when(repository.findActivePromotionsForTarget(applicationId, Environment.DEV))
				.thenReturn(List.of(first));

		assertThatThrownBy(() -> service.requestPromotion(applicationId, version, Environment.DEV, requester))
				.isInstanceOf(DuplicatePromotionInProgressError.class);
	}

	@Test
	void requestingAgainAfterCancellingTheFirstOneIsAllowed() {
		PromotionId first = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion firstPromotion = savedPromotion();
		when(repository.findById(first)).thenReturn(Optional.of(firstPromotion));
		service.cancelPromotion(first, requester);

		when(repository.findActivePromotionsForTarget(applicationId, Environment.DEV))
				.thenReturn(List.of(firstPromotion));

		PromotionId second = service.requestPromotion(applicationId, version, Environment.DEV, requester);

		assertThat(second).isNotEqualTo(first);
	}

	@Test
	void completingAPromotionUnlocksTheNextEnvironmentForTheSameVersion() {
		when(deploymentPort.trigger(any(), any(), any())).thenReturn(new DeploymentRef("deploy-1"));
		PromotionId devPromotion = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(devPromotion)).thenReturn(Optional.of(promotion));

		service.approvePromotion(devPromotion, approver);
		service.startDeployment(devPromotion, approver);
		service.completePromotion(devPromotion, approver);

		when(repository.findLastCompletedEnvironment(applicationId, version))
				.thenReturn(Optional.of(Environment.DEV));

		PromotionId stagingPromotion =
				service.requestPromotion(applicationId, version, Environment.STAGING, requester);

		assertThat(stagingPromotion).isNotNull();
	}

	@Test
	void approvingWithoutTheApproverRoleIsRejectedThroughThePort() {
		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));

		assertThatThrownBy(() -> service.approvePromotion(id, requester))
				.isInstanceOf(UnauthorizedApproverError.class);
	}

	@Test
	void commandsAgainstAnUnknownPromotionAreRejected() {
		PromotionId unknown = PromotionId.random();

		assertThatThrownBy(() -> service.approvePromotion(unknown, approver))
				.isInstanceOf(PromotionNotFoundException.class);
	}

	/** Captures the {@link Promotion} most recently handed to {@link PromotionRepositoryPort#save}. */
	private Promotion savedPromotion() {
		ArgumentCaptor<Promotion> captor = ArgumentCaptor.forClass(Promotion.class);
		verify(repository, atLeastOnce()).save(captor.capture());
		return captor.getValue();
	}

	// --- every command publishes its event through EventPublisherPort after persisting ---

	@Test
	void requestPromotionPublishesAPromotionRequestedEvent() {
		service.requestPromotion(applicationId, version, Environment.DEV, requester);

		DomainEvent event = publishedEvent();
		assertThat(event.eventType()).isEqualTo(Promotion.EVENT_PROMOTION_REQUESTED);
		assertThat(event.applicationId()).isEqualTo(applicationId);
		assertThat(event.actingUser()).isEqualTo(requester.userId());
		assertThat(event.payload())
				.containsEntry("version", version.value())
				.containsEntry("targetEnvironment", "DEV");
	}

	@Test
	void approvePromotionPublishesAPromotionApprovedEventCarryingTheApproverId() {
		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));

		service.approvePromotion(id, approver);

		DomainEvent event = publishedEvent();
		assertThat(event.eventType()).isEqualTo(Promotion.EVENT_PROMOTION_APPROVED);
		assertThat(event.payload()).containsEntry("approvedBy", approver.userId());
	}

	@Test
	void startDeploymentPublishesADeploymentStartedEventCarryingTheDeploymentRef() {
		when(deploymentPort.trigger(any(), any(), any())).thenReturn(new DeploymentRef("deploy-1"));
		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));
		service.approvePromotion(id, approver);

		service.startDeployment(id, approver);

		DomainEvent event = publishedEvent();
		assertThat(event.eventType()).isEqualTo(Promotion.EVENT_DEPLOYMENT_STARTED);
		assertThat(event.payload()).containsEntry("deploymentRef", "deploy-1");
	}

	@Test
	void completePromotionPublishesAPromotionCompletedEvent() {
		when(deploymentPort.trigger(any(), any(), any())).thenReturn(new DeploymentRef("deploy-1"));
		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));
		service.approvePromotion(id, approver);
		service.startDeployment(id, approver);

		service.completePromotion(id, approver);

		assertThat(publishedEvent().eventType()).isEqualTo(Promotion.EVENT_PROMOTION_COMPLETED);
	}

	@Test
	void rollbackPromotionPublishesAPromotionRolledBackEvent() {
		when(deploymentPort.trigger(any(), any(), any())).thenReturn(new DeploymentRef("deploy-1"));
		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));
		service.approvePromotion(id, approver);
		service.startDeployment(id, approver);

		service.rollbackPromotion(id, approver);

		assertThat(publishedEvent().eventType()).isEqualTo(Promotion.EVENT_PROMOTION_ROLLED_BACK);
	}

	@Test
	void cancelPromotionPublishesAPromotionCancelledEvent() {
		PromotionId id = service.requestPromotion(applicationId, version, Environment.DEV, requester);
		Promotion promotion = savedPromotion();
		when(repository.findById(id)).thenReturn(Optional.of(promotion));

		service.cancelPromotion(id, requester);

		assertThat(publishedEvent().eventType()).isEqualTo(Promotion.EVENT_PROMOTION_CANCELLED);
	}

	/** Captures the {@link DomainEvent} most recently handed to {@link EventPublisherPort#publish}. */
	private DomainEvent publishedEvent() {
		ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
		verify(eventPublisher, atLeastOnce()).publish(captor.capture());
		return captor.getValue();
	}
}
