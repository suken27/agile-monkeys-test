package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;

import java.util.Optional;

/**
 * Output port: read-only access to the denormalized projections populated by the read-model
 * projector consumer (SPECS §8), never the write-side {@code promotions} table. Kept separate
 * from {@code PromotionRepositoryPort} (the write side's persistence port) so the two paths of
 * CQRS stay decoupled all the way down to persistence.
 */
public interface PromotionReadModelPort {

	Optional<PromotionDetail> findPromotionDetail(PromotionId promotionId);

	ApplicationEnvironmentStatus findApplicationEnvironmentStatus(ApplicationId applicationId);

	PromotionHistoryPage findApplicationPromotions(ApplicationId applicationId, int page, int pageSize);
}
