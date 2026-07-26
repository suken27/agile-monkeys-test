package com.releasepilot.application.commands;

import com.releasepilot.application.PromotionNotFoundException;
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
import com.releasepilot.domain.promotion.Version;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Implementation of the {@link PromotionCommandPort} — the only port implemented in this layer.
 * Loads the sibling promotions each invariant needs (§3.4) through {@link PromotionRepositoryPort},
 * then lets the {@link Promotion} aggregate itself decide and guard every transition.
 *
 * <p>After every command persists the aggregate's new state, the handler pulls the event(s) that
 * transition recorded on the aggregate and publishes them through {@link EventPublisherPort}
 * (SPECS §5.1) — the state write and the publish call happen back-to-back in the same handler
 * invocation so the outbox adapter backing the port can enlist both in one DB transaction.
 *
 * <p>Each method is {@code @Transactional} so the aggregate's persisted state and its outbox
 * row(s) commit or roll back together (SPECS §5.1) — the queue publish and downstream consumers
 * run after this transaction commits, decoupled from the command-handling call stack.
 */
@Service
public class PromotionCommandService implements PromotionCommandPort {

	private final DeploymentPort deploymentPort;
	private final PromotionRepositoryPort repository;
	private final EventPublisherPort eventPublisher;

	public PromotionCommandService(
			DeploymentPort deploymentPort, PromotionRepositoryPort repository, EventPublisherPort eventPublisher) {
		this.deploymentPort = deploymentPort;
		this.repository = repository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public PromotionId requestPromotion(
			ApplicationId applicationId, Version version, Environment targetEnvironment, Actor requestedBy) {
		Environment lastCompleted = repository.findLastCompletedEnvironment(applicationId, version).orElse(null);
		List<Promotion> existingForTarget =
				repository.findActivePromotionsForTarget(applicationId, targetEnvironment);
		Promotion promotion = Promotion.request(
				PromotionId.random(), applicationId, version, lastCompleted, targetEnvironment, requestedBy,
				existingForTarget);
		repository.save(promotion);
		publishEvents(promotion);
		return promotion.id();
	}

	@Override
	@Transactional
	public PromotionId approvePromotion(PromotionId promotionId, Actor approvedBy) {
		Promotion promotion = load(promotionId);
		promotion.approve(approvedBy);
		repository.save(promotion);
		publishEvents(promotion);
		return promotion.id();
	}

	@Override
	@Transactional
	public PromotionId startDeployment(PromotionId promotionId, Actor startedBy) {
		Promotion promotion = load(promotionId);
		promotion.startDeployment(startedBy);
		DeploymentRef deploymentRef =
				deploymentPort.trigger(promotion.applicationId(), promotion.version(), promotion.targetEnvironment());
		repository.save(promotion);
		publishEvents(promotion, Map.of("deploymentRef", deploymentRef.value()));
		return promotion.id();
	}

	@Override
	@Transactional
	public PromotionId completePromotion(PromotionId promotionId, Actor completedBy) {
		Promotion promotion = load(promotionId);
		promotion.complete(completedBy);
		repository.save(promotion);
		publishEvents(promotion);
		return promotion.id();
	}

	@Override
	public PromotionId rollbackPromotion(PromotionId promotionId, Actor rolledBackBy) {
		return rollbackPromotion(promotionId, rolledBackBy, null);
	}

	@Override
	@Transactional
	public PromotionId rollbackPromotion(PromotionId promotionId, Actor rolledBackBy, String reason) {
		Promotion promotion = load(promotionId);
		promotion.rollback(rolledBackBy, reason);
		repository.save(promotion);
		publishEvents(promotion);
		return promotion.id();
	}

	@Override
	public PromotionId cancelPromotion(PromotionId promotionId, Actor cancelledBy) {
		return cancelPromotion(promotionId, cancelledBy, null);
	}

	@Override
	@Transactional
	public PromotionId cancelPromotion(PromotionId promotionId, Actor cancelledBy, String reason) {
		Promotion promotion = load(promotionId);
		promotion.cancel(cancelledBy, reason);
		repository.save(promotion);
		publishEvents(promotion);
		return promotion.id();
	}

	private Promotion load(PromotionId promotionId) {
		return repository.findById(promotionId)
				.orElseThrow(() -> new PromotionNotFoundException(promotionId));
	}

	private void publishEvents(Promotion promotion) {
		promotion.pullDomainEvents().forEach(eventPublisher::publish);
	}

	/** Enriches every event pulled from this transition with {@code extraPayload} before publishing. */
	private void publishEvents(Promotion promotion, Map<String, Object> extraPayload) {
		for (DomainEvent event : promotion.pullDomainEvents()) {
			eventPublisher.publish(event.withPayload(extraPayload));
		}
	}
}
