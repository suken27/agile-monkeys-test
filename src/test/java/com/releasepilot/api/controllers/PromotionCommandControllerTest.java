package com.releasepilot.api.controllers;

import com.releasepilot.application.PromotionNotFoundException;
import com.releasepilot.application.commands.PromotionCommandPort;
import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;
import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
import com.releasepilot.domain.promotion.errors.UnauthorizedApproverError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the thin HTTP boundary for the write side end to end at the servlet layer, including
 * {@link com.releasepilot.api.ErrorMapping}'s status codes (SPECS §10) — the port itself is
 * mocked, since the aggregate's business rules are already covered by {@code PromotionTest} and
 * {@code PromotionCommandServiceTest}.
 */
@WebMvcTest(PromotionCommandController.class)
class PromotionCommandControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PromotionCommandPort commandPort;

	private final PromotionId promotionId = PromotionId.random();

	@Test
	void requestPromotionReturns201WithLocationAndBody() throws Exception {
		ApplicationId applicationId = ApplicationId.random();
		when(commandPort.requestPromotion(eq(applicationId), eq(new Version("1.4.0")), eq(Environment.DEV), any()))
				.thenReturn(promotionId);

		mockMvc.perform(post("/promotions")
						.contentType("application/json")
						.content("""
								{
								  "applicationId": "%s",
								  "version": "1.4.0",
								  "targetEnvironment": "DEV",
								  "requestedBy": { "userId": "alice", "role": "REQUESTER" }
								}
								""".formatted(applicationId.value())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(promotionId.value().toString()));
	}

	@Test
	void requestPromotionWithAnUnknownRoleReturns400() throws Exception {
		mockMvc.perform(post("/promotions")
						.contentType("application/json")
						.content("""
								{
								  "applicationId": "%s",
								  "version": "1.4.0",
								  "targetEnvironment": "DEV",
								  "requestedBy": { "userId": "alice", "role": "NOT_A_ROLE" }
								}
								""".formatted(ApplicationId.random().value())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requestPromotionSkippingAnEnvironmentReturns422() throws Exception {
		when(commandPort.requestPromotion(any(), any(), eq(Environment.PRODUCTION), any()))
				.thenThrow(new EnvironmentSkippedError(Environment.DEV, Environment.PRODUCTION));

		mockMvc.perform(post("/promotions")
						.contentType("application/json")
						.content("""
								{
								  "applicationId": "%s",
								  "version": "1.4.0",
								  "targetEnvironment": "PRODUCTION",
								  "requestedBy": { "userId": "alice", "role": "REQUESTER" }
								}
								""".formatted(ApplicationId.random().value())))
				.andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()));
	}

	@Test
	void requestPromotionWithADuplicateInFlightReturns409() throws Exception {
		when(commandPort.requestPromotion(any(), any(), any(), any()))
				.thenThrow(new DuplicatePromotionInProgressError(ApplicationId.random(), Environment.DEV));

		mockMvc.perform(post("/promotions")
						.contentType("application/json")
						.content("""
								{
								  "applicationId": "%s",
								  "version": "1.4.0",
								  "targetEnvironment": "DEV",
								  "requestedBy": { "userId": "alice", "role": "REQUESTER" }
								}
								""".formatted(ApplicationId.random().value())))
				.andExpect(status().isConflict());
	}

	@Test
	void approveWithoutTheApproverRoleReturns403() throws Exception {
		when(commandPort.approvePromotion(eq(promotionId), any()))
				.thenThrow(new UnauthorizedApproverError("alice"));

		mockMvc.perform(post("/promotions/" + promotionId.value() + "/approve")
						.contentType("application/json")
						.content("""
								{ "approvedBy": { "userId": "alice", "role": "REQUESTER" } }
								"""))
				.andExpect(status().isForbidden());
	}

	@Test
	void approveAnUnknownPromotionReturns404() throws Exception {
		when(commandPort.approvePromotion(eq(promotionId), any()))
				.thenThrow(new PromotionNotFoundException(promotionId));

		mockMvc.perform(post("/promotions/" + promotionId.value() + "/approve")
						.contentType("application/json")
						.content("""
								{ "approvedBy": { "userId": "bob", "role": "APPROVER" } }
								"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void rollbackPassesTheOptionalReasonThrough() throws Exception {
		when(commandPort.rollbackPromotion(eq(promotionId), any(Actor.class), eq("smoke tests failed")))
				.thenReturn(promotionId);

		mockMvc.perform(post("/promotions/" + promotionId.value() + "/rollback")
						.contentType("application/json")
						.content("""
								{ "rolledBackBy": { "userId": "bob", "role": "APPROVER" }, "reason": "smoke tests failed" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(promotionId.value().toString()));
	}

	@Test
	void cancelWithoutAReasonPassesNullThrough() throws Exception {
		when(commandPort.cancelPromotion(eq(promotionId), any(Actor.class), isNull())).thenReturn(promotionId);

		mockMvc.perform(post("/promotions/" + promotionId.value() + "/cancel")
						.contentType("application/json")
						.content("""
								{ "cancelledBy": { "userId": "alice", "role": "REQUESTER" } }
								"""))
				.andExpect(status().isOk());
	}
}
