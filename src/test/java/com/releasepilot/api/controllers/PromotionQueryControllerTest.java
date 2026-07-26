package com.releasepilot.api.controllers;

import com.releasepilot.application.queries.ApplicationEnvironmentStatus;
import com.releasepilot.application.queries.PromotionDetail;
import com.releasepilot.application.queries.PromotionHistoryPage;
import com.releasepilot.application.queries.PromotionQueryPort;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the thin HTTP boundary for the read side (SPECS §7/§10) at the servlet layer. */
@WebMvcTest(PromotionQueryController.class)
class PromotionQueryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PromotionQueryPort queryPort;

	@Test
	void getPromotionDetailReturns200WithTheFlattenedShape() throws Exception {
		PromotionId promotionId = PromotionId.random();
		ApplicationId applicationId = ApplicationId.random();
		PromotionDetail detail = new PromotionDetail(
				promotionId, applicationId, new Version("1.4.0"), null, Environment.DEV,
				PromotionStatus.REQUESTED, "alice", null, List.of());
		when(queryPort.getPromotionDetail(promotionId)).thenReturn(Optional.of(detail));

		mockMvc.perform(get("/promotions/" + promotionId.value()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(promotionId.value().toString()))
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andExpect(jsonPath("$.requestedBy").value("alice"));
	}

	@Test
	void getPromotionDetailReturns404WhenMissing() throws Exception {
		PromotionId promotionId = PromotionId.random();
		when(queryPort.getPromotionDetail(promotionId)).thenReturn(Optional.empty());

		mockMvc.perform(get("/promotions/" + promotionId.value())).andExpect(status().isNotFound());
	}

	@Test
	void getPromotionDetailWithAMalformedIdReturns400() throws Exception {
		mockMvc.perform(get("/promotions/not-a-uuid")).andExpect(status().isBadRequest());
	}

	@Test
	void getApplicationStatusReturnsAllThreeEnvironments() throws Exception {
		ApplicationId applicationId = ApplicationId.random();
		ApplicationEnvironmentStatus status = new ApplicationEnvironmentStatus(
				applicationId,
				List.of(
						new ApplicationEnvironmentStatus.EnvironmentStatus(
								Environment.DEV, new Version("1.4.0"), PromotionStatus.COMPLETED, PromotionId.random()),
						new ApplicationEnvironmentStatus.EnvironmentStatus(Environment.STAGING, null, null, null),
						new ApplicationEnvironmentStatus.EnvironmentStatus(Environment.PRODUCTION, null, null, null)));
		when(queryPort.getApplicationStatus(applicationId)).thenReturn(status);

		mockMvc.perform(get("/applications/" + applicationId.value() + "/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.environments", org.hamcrest.Matchers.hasSize(3)))
				.andExpect(jsonPath("$.environments[0].environment").value("DEV"))
				.andExpect(jsonPath("$.environments[0].currentVersion").value("1.4.0"))
				.andExpect(jsonPath("$.environments[1].status").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void getApplicationPromotionsDefaultsPageAndPageSize() throws Exception {
		ApplicationId applicationId = ApplicationId.random();
		when(queryPort.getApplicationPromotions(eq(applicationId), eq(0), eq(20)))
				.thenReturn(new PromotionHistoryPage(List.of(), 0, 20, 0));

		mockMvc.perform(get("/applications/" + applicationId.value() + "/promotions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.pageSize").value(20))
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void getApplicationPromotionsPassesExplicitPagingParams() throws Exception {
		ApplicationId applicationId = ApplicationId.random();
		when(queryPort.getApplicationPromotions(eq(applicationId), eq(2), eq(5)))
				.thenReturn(new PromotionHistoryPage(List.of(), 2, 5, 11));

		mockMvc.perform(get("/applications/" + applicationId.value() + "/promotions?page=2&pageSize=5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.pageSize").value(5))
				.andExpect(jsonPath("$.total").value(11));
	}
}
