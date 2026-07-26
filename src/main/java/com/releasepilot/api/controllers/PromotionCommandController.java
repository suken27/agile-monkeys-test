package com.releasepilot.api.controllers;

import com.releasepilot.api.dto.commands.ApprovePromotionRequest;
import com.releasepilot.api.dto.commands.CancelPromotionRequest;
import com.releasepilot.api.dto.commands.CompletePromotionRequest;
import com.releasepilot.api.dto.commands.PromotionIdResponse;
import com.releasepilot.api.dto.commands.RequestPromotionRequest;
import com.releasepilot.api.dto.commands.RollbackPromotionRequest;
import com.releasepilot.api.dto.commands.StartDeploymentRequest;
import com.releasepilot.application.commands.PromotionCommandPort;
import com.releasepilot.domain.promotion.PromotionId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Thin HTTP boundary for the write side (SPECS §10): parses the request, builds the arguments a
 * {@link PromotionCommandPort} method needs, and delegates — no business logic lives here. Every
 * business-rule violation raised by the aggregate propagates as a {@link
 * com.releasepilot.domain.promotion.errors.DomainError} and is translated to a 4xx response by
 * {@link com.releasepilot.api.ErrorMapping}, never handled inline in this class.
 */
@RestController
@RequestMapping("/promotions")
public class PromotionCommandController {

	private final PromotionCommandPort commandPort;

	public PromotionCommandController(PromotionCommandPort commandPort) {
		this.commandPort = commandPort;
	}

	@PostMapping
	public ResponseEntity<PromotionIdResponse> requestPromotion(@RequestBody RequestPromotionRequest request) {
		PromotionId id = commandPort.requestPromotion(
				request.applicationIdValue(), request.versionValue(), request.targetEnvironmentValue(),
				request.requestedByActor());
		return ResponseEntity.created(URI.create("/promotions/" + id.value())).body(PromotionIdResponse.from(id));
	}

	@PostMapping("/{id}/approve")
	public PromotionIdResponse approve(@PathVariable String id, @RequestBody ApprovePromotionRequest request) {
		return PromotionIdResponse.from(
				commandPort.approvePromotion(PromotionId.of(id), request.approvedByActor()));
	}

	@PostMapping("/{id}/start")
	public PromotionIdResponse start(@PathVariable String id, @RequestBody StartDeploymentRequest request) {
		return PromotionIdResponse.from(
				commandPort.startDeployment(PromotionId.of(id), request.startedByActor()));
	}

	@PostMapping("/{id}/complete")
	public PromotionIdResponse complete(@PathVariable String id, @RequestBody CompletePromotionRequest request) {
		return PromotionIdResponse.from(
				commandPort.completePromotion(PromotionId.of(id), request.completedByActor()));
	}

	@PostMapping("/{id}/rollback")
	public PromotionIdResponse rollback(@PathVariable String id, @RequestBody RollbackPromotionRequest request) {
		return PromotionIdResponse.from(
				commandPort.rollbackPromotion(PromotionId.of(id), request.rolledBackByActor(), request.reason()));
	}

	@PostMapping("/{id}/cancel")
	public PromotionIdResponse cancel(@PathVariable String id, @RequestBody CancelPromotionRequest request) {
		return PromotionIdResponse.from(
				commandPort.cancelPromotion(PromotionId.of(id), request.cancelledByActor(), request.reason()));
	}
}
