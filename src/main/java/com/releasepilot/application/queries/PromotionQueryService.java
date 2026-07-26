package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementation of {@link PromotionQueryPort}. Each method is a thin pass-through to
 * {@link PromotionReadModelPort} — per CQRS (SPECS §2), the read side has no business logic of
 * its own to apply; the shape returned here already is the read model.
 */
@Service
public class PromotionQueryService implements PromotionQueryPort {

	private final PromotionReadModelPort readModel;

	public PromotionQueryService(PromotionReadModelPort readModel) {
		this.readModel = readModel;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PromotionDetail> getPromotionDetail(PromotionId promotionId) {
		return readModel.findPromotionDetail(promotionId);
	}

	@Override
	@Transactional(readOnly = true)
	public ApplicationEnvironmentStatus getApplicationStatus(ApplicationId applicationId) {
		return readModel.findApplicationEnvironmentStatus(applicationId);
	}

	@Override
	@Transactional(readOnly = true)
	public PromotionHistoryPage getApplicationPromotions(ApplicationId applicationId, int page, int pageSize) {
		if (page < 0) {
			throw new IllegalArgumentException("page must not be negative");
		}
		if (pageSize < 1) {
			throw new IllegalArgumentException("pageSize must be at least 1");
		}
		return readModel.findApplicationPromotions(applicationId, page, pageSize);
	}
}
