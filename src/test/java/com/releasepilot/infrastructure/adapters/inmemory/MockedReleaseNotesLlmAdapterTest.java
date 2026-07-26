package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.application.queries.PromotionHistoryPage.PromotionHistoryItem;
import com.releasepilot.domain.ports.AgentContext;
import com.releasepilot.domain.ports.AgentDecision;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import com.releasepilot.domain.ports.WorkItem;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.PromotionStatus;
import com.releasepilot.domain.promotion.Version;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the mocked LLM's decisions directly (SPECS §9) — no consumer, no database, no broker —
 * proving the deterministic "model" walks the loop shape the spec describes: fetch work items,
 * re-fetch once if that came back empty, fetch promotion history, save a draft referencing whatever
 * was found, then finish.
 */
class MockedReleaseNotesLlmAdapterTest {

	private final MockedReleaseNotesLlmAdapter llm = new MockedReleaseNotesLlmAdapter();
	private final PromotionId promotionId = PromotionId.random();
	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("1.2.0");

	@Test
	void firstDecisionAlwaysFetchesLinkedWorkItems() {
		AgentContext context = AgentContext.start(promotionId, applicationId, version);

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS);
		assertThat(callTool.arguments()).containsEntry("applicationId", applicationId).containsEntry("version", version);
	}

	@Test
	void reFetchesLinkedWorkItemsOnceWhenTheFirstFetchCameBackEmpty() {
		AgentContext context = AgentContext.start(promotionId, applicationId, version)
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of());

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		assertThat(((AgentDecision.CallTool) decision).toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS);
	}

	@Test
	void movesOnToPromotionHistoryAfterASecondEmptyFetch() {
		AgentContext context = AgentContext.start(promotionId, applicationId, version)
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of())
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of());

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		assertThat(((AgentDecision.CallTool) decision).toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_GET_PROMOTION_HISTORY);
	}

	@Test
	void movesOnToPromotionHistoryImmediatelyWhenWorkItemsWereFound() {
		WorkItem workItem = new WorkItem("TICKET-1", "Fix the thing", "https://tracker.example/TICKET-1");
		AgentContext context = AgentContext.start(promotionId, applicationId, version)
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of(workItem));

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		assertThat(((AgentDecision.CallTool) decision).toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_GET_PROMOTION_HISTORY);
	}

	@Test
	void savesADraftReferencingTheLinkedWorkItemsAndHistoryThenFinishes() {
		WorkItem workItem = new WorkItem("TICKET-1", "Fix the thing", "https://tracker.example/TICKET-1");
		PromotionHistoryItem historyItem = new PromotionHistoryItem(
				PromotionId.random(), new Version("1.1.0"), Environment.DEV, Environment.STAGING,
				PromotionStatus.COMPLETED, Instant.now(), Instant.now());
		AgentContext context = AgentContext.start(promotionId, applicationId, version)
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of(workItem))
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_PROMOTION_HISTORY, List.of(historyItem));

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_SAVE_RELEASE_NOTES);
		assertThat(callTool.arguments()).containsEntry("promotionId", promotionId);
		assertThat((String) callTool.arguments().get("draftText"))
				.contains("TICKET-1", "Fix the thing", "1.1.0");

		AgentContext afterSave = context.withToolResult(ReleaseNotesLlmPort.TOOL_SAVE_RELEASE_NOTES, null);
		assertThat(llm.decide(afterSave)).isInstanceOf(AgentDecision.Done.class);
	}

	@Test
	void draftsAnywayNotingNoLinkedWorkItemsWhenBothFetchesCameBackEmpty() {
		AgentContext context = AgentContext.start(promotionId, applicationId, version)
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of())
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS, List.of())
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_PROMOTION_HISTORY, List.of());

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_SAVE_RELEASE_NOTES);
		assertThat((String) callTool.arguments().get("draftText")).contains("No linked work items found");
	}
}
