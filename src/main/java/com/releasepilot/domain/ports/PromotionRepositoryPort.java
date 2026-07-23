package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;

import java.util.List;
import java.util.Optional;

/**
 * Output port: the write-side persistence capability the application layer requires for the
 * {@link Promotion} aggregate. Filtering for the invariants in {@code Promotion.request} (§3.4)
 * is the implementing adapter's responsibility, not the caller's — the port exposes exactly the
 * queries those invariants need, already scoped.
 */
public interface PromotionRepositoryPort {

	void save(Promotion promotion);

	Optional<Promotion> findById(PromotionId promotionId);

	/**
	 * Non-terminal promotions for this (applicationId, targetEnvironment) pair, for invariant #2.
	 * Completed/rolled-back/cancelled promotions are irrelevant to that check, so the adapter
	 * scopes the query to active ones rather than returning the full history.
	 */
	List<Promotion> findActivePromotionsForTarget(ApplicationId applicationId, Environment targetEnvironment);

	/** The highest environment this application/version pair has completed, for invariant #1. */
	Optional<Environment> findLastCompletedEnvironment(ApplicationId applicationId, Version version);
}
