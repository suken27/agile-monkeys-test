package com.releasepilot.api.dto.commands;

import com.releasepilot.domain.promotion.PromotionId;

import java.util.UUID;

/** Response body shared by every command endpoint (SPECS §10). */
public record PromotionIdResponse(UUID id) {

	public static PromotionIdResponse from(PromotionId promotionId) {
		return new PromotionIdResponse(promotionId.value());
	}
}
