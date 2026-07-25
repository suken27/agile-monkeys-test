package com.releasepilot.application.commands;

import com.releasepilot.domain.ports.DeploymentPort;
import com.releasepilot.domain.ports.PromotionRepositoryPort;
import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;

import java.util.List;

/**
 * Implementation of the {@link PromotionCommandPort} — the only port implemented in this layer.
 * Loads the sibling promotions each invariant needs (§3.4) through {@link PromotionRepositoryPort},
 * then lets the {@link Promotion} aggregate itself decide and guard every transition.
 */
public class PromotionCommandService implements PromotionCommandPort {

	private final DeploymentPort deploymentPort;
	private final PromotionRepositoryPort repository;

	public PromotionCommandService(DeploymentPort deploymentPort, PromotionRepositoryPort repository) {
		this.deploymentPort = deploymentPort;
		this.repository = repository;
	}

	@Override
	public PromotionId requestPromotion(
			ApplicationId applicationId, Version version, Environment targetEnvironment, Actor requestedBy) {
		Environment lastCompleted = repository.findLastCompletedEnvironment(applicationId, version).orElse(null);
		List<Promotion> existingForTarget =
				repository.findActivePromotionsForTarget(applicationId, targetEnvironment);
		Promotion promotion = Promotion.request(
				PromotionId.random(), applicationId, version, lastCompleted, targetEnvironment, requestedBy,
				existingForTarget);
		repository.save(promotion);
		return promotion.id();
	}

	@Override
	public PromotionId approvePromotion(PromotionId promotionId, Actor approvedBy) {
		Promotion promotion = load(promotionId);
		promotion.approve(approvedBy);
		repository.save(promotion);
		return promotion.id();
	}

	@Override
	public PromotionId startDeployment(PromotionId promotionId, Actor startedBy) {
		Promotion promotion = load(promotionId);
		promotion.startDeployment(startedBy);
		repository.save(promotion);
		deploymentPort.trigger(promotion.applicationId(), promotion.version(), promotion.targetEnvironment());
		return promotion.id();
	}

	@Override
	public PromotionId completePromotion(PromotionId promotionId, Actor completedBy) {
		Promotion promotion = load(promotionId);
		promotion.complete(completedBy);
		repository.save(promotion);
		return promotion.id();
	}

	@Override
	public PromotionId rollbackPromotion(PromotionId promotionId, Actor rolledBackBy) {
		Promotion promotion = load(promotionId);
		promotion.rollback(rolledBackBy);
		repository.save(promotion);
		return promotion.id();
	}

	@Override
	public PromotionId cancelPromotion(PromotionId promotionId, Actor cancelledBy) {
		Promotion promotion = load(promotionId);
		promotion.cancel(cancelledBy);
		repository.save(promotion);
		return promotion.id();
	}

	private Promotion load(PromotionId promotionId) {
		return repository.findById(promotionId)
				.orElseThrow(() -> new PromotionNotFoundException(promotionId));
	}
}
