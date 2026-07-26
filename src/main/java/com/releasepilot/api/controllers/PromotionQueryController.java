package com.releasepilot.api.controllers;

import com.releasepilot.api.dto.queries.ApplicationStatusResponse;
import com.releasepilot.api.dto.queries.PromotionDetailResponse;
import com.releasepilot.api.dto.queries.PromotionHistoryResponse;
import com.releasepilot.application.PromotionNotFoundException;
import com.releasepilot.application.queries.PromotionQueryPort;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP boundary for the read side (SPECS §7/§10): delegates straight to {@link
 * PromotionQueryPort} and flattens the result into a JSON-shaped response — no query logic lives
 * here, and this class never touches the write-side aggregate or its repository.
 */
@RestController
public class PromotionQueryController {

	private final PromotionQueryPort queryPort;

	public PromotionQueryController(PromotionQueryPort queryPort) {
		this.queryPort = queryPort;
	}

	@GetMapping("/promotions/{id}")
	public PromotionDetailResponse getPromotionDetail(@PathVariable String id) {
		PromotionId promotionId = PromotionId.of(id);
		return queryPort.getPromotionDetail(promotionId)
				.map(PromotionDetailResponse::from)
				.orElseThrow(() -> new PromotionNotFoundException(promotionId));
	}

	@GetMapping("/applications/{id}/status")
	public ApplicationStatusResponse getApplicationStatus(@PathVariable String id) {
		return ApplicationStatusResponse.from(queryPort.getApplicationStatus(ApplicationId.of(id)));
	}

	@GetMapping("/applications/{id}/promotions")
	public PromotionHistoryResponse getApplicationPromotions(
			@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int pageSize) {
		return PromotionHistoryResponse.from(queryPort.getApplicationPromotions(ApplicationId.of(id), page, pageSize));
	}
}
