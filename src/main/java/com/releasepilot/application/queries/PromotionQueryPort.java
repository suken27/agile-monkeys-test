package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;

import java.util.Optional;

/**
 * Input port: the seam through which driving adapters (HTTP controllers) reach the read side.
 * One method per query in the spec (§7); each queries a denormalized read-model projection
 * directly and never touches the {@code Promotion} aggregate or its write-side repository.
 */
public interface PromotionQueryPort {

	Optional<PromotionDetail> getPromotionDetail(PromotionId promotionId);

	ApplicationEnvironmentStatus getApplicationStatus(ApplicationId applicationId);

	PromotionHistoryPage getApplicationPromotions(ApplicationId applicationId, int page, int pageSize);
}
