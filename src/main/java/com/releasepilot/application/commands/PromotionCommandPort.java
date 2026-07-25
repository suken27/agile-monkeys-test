package com.releasepilot.application.commands;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;

/**
 * Input port: the seam through which driving adapters (HTTP controllers) reach the write side.
 * One method per command in the spec (§4); each loads/persists the {@code Promotion} aggregate
 * and lets it enforce its own invariants, never re-implementing business rules here.
 */
public interface PromotionCommandPort {

	PromotionId requestPromotion(
			ApplicationId applicationId, Version version, Environment targetEnvironment, Actor requestedBy);

	PromotionId approvePromotion(PromotionId promotionId, Actor approvedBy);

	PromotionId startDeployment(PromotionId promotionId, Actor startedBy);

	PromotionId completePromotion(PromotionId promotionId, Actor completedBy);

	PromotionId rollbackPromotion(PromotionId promotionId, Actor rolledBackBy);

	PromotionId cancelPromotion(PromotionId promotionId, Actor cancelledBy);
}
