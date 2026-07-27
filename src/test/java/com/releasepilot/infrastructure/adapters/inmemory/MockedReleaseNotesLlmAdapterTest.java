package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.ports.AgentContext;
import com.releasepilot.domain.ports.AgentDecision;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import com.releasepilot.domain.ports.WorkItem;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the mocked LLM's decisions directly (SPECS §9) — no consumer, no database, no broker —
 * proving the deterministic "model" walks the loop shape the spec describes: fetch work items, ask
 * clarification for any that are too thin to summarize, flag any that read as a breaking change,
 * submit a draft referencing whatever was found, then finish.
 */
class MockedReleaseNotesLlmAdapterTest {

	private final MockedReleaseNotesLlmAdapter llm = new MockedReleaseNotesLlmAdapter();
	private final PromotionId promotionId = PromotionId.random();
	private final ApplicationId applicationId = ApplicationId.random();
	private final Version version = new Version("1.2.0");

	@Test
	void firstDecisionAlwaysFetchesWorkItems() {
		AgentContext context = AgentContext.start(promotionId, applicationId, version);

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS);
		assertThat(callTool.arguments()).containsEntry("promotionId", promotionId);
	}

	@Test
	void asksClarificationForAWorkItemMissingADescription() {
		WorkItem thin = new WorkItem("TICKET-1", "Improve caching", "https://tracker.example/TICKET-1", null);
		AgentContext context = start().withToolResult(
				ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS, Map.of("promotionId", promotionId), List.of(thin));

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_ASK_CLARIFICATION);
		assertThat(callTool.arguments()).containsEntry("workItemId", "TICKET-1");
	}

	@Test
	void doesNotAskClarificationAgainOnceAnAnswerWasRecordedForThatWorkItem() {
		WorkItem thin = new WorkItem("TICKET-1", "Improve caching", "https://tracker.example/TICKET-1", null);
		AgentContext context = start()
				.withToolResult(ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS, Map.of("promotionId", promotionId), List.of(thin))
				.withToolResult(
						ReleaseNotesLlmPort.TOOL_ASK_CLARIFICATION,
						Map.of("workItemId", "TICKET-1", "question", "clarify?"),
						"Only affects internal caching.");

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		assertThat(((AgentDecision.CallTool) decision).toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_SUBMIT_RELEASE_NOTES);
	}

	@Test
	void flagsAWorkItemThatMentionsABreakingChange() {
		WorkItem breaking = new WorkItem(
				"TICKET-2", "Remove legacy auth endpoint", "https://tracker.example/TICKET-2",
				"This is a breaking change for API v1 clients.");
		AgentContext context = start().withToolResult(
				ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS, Map.of("promotionId", promotionId), List.of(breaking));

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_FLAG_BREAKING_CHANGE);
		assertThat(callTool.arguments()).containsEntry("workItemId", "TICKET-2");
	}

	@Test
	void movesStraightToSubmittingWhenNoWorkItemNeedsClarificationOrFlagging() {
		WorkItem clear = new WorkItem("TICKET-3", "Fix typo in footer", "https://tracker.example/TICKET-3", "Fixed a typo.");
		AgentContext context = start().withToolResult(
				ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS, Map.of("promotionId", promotionId), List.of(clear));

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		assertThat(((AgentDecision.CallTool) decision).toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_SUBMIT_RELEASE_NOTES);
	}

	@Test
	void submitsADraftReferencingClarificationsAndBreakingChangesThenFinishes() {
		WorkItem thin = new WorkItem("TICKET-1", "Improve caching", "https://tracker.example/TICKET-1", null);
		WorkItem breaking = new WorkItem(
				"TICKET-2", "Remove legacy auth endpoint", "https://tracker.example/TICKET-2",
				"This is a breaking change for API v1 clients.");
		AgentContext context = start()
				.withToolResult(
						ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS, Map.of("promotionId", promotionId), List.of(thin, breaking))
				.withToolResult(
						ReleaseNotesLlmPort.TOOL_ASK_CLARIFICATION,
						Map.of("workItemId", "TICKET-1", "question", "clarify?"),
						"Only affects internal caching.")
				.withToolResult(
						ReleaseNotesLlmPort.TOOL_FLAG_BREAKING_CHANGE,
						Map.of("workItemId", "TICKET-2", "reason", "removes a public endpoint"),
						null);

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_SUBMIT_RELEASE_NOTES);
		String draftText = (String) callTool.arguments().get("draft");
		assertThat(draftText).contains(
				"TICKET-1", "Improve caching", "Only affects internal caching.",
				"TICKET-2", "removes a public endpoint");

		AgentContext afterSubmit = context.withToolResult(
				ReleaseNotesLlmPort.TOOL_SUBMIT_RELEASE_NOTES, Map.of("draft", draftText), null);
		assertThat(llm.decide(afterSubmit)).isInstanceOf(AgentDecision.Done.class);
	}

	@Test
	void draftsAnywayWhenThereAreNoLinkedWorkItems() {
		AgentContext context = start().withToolResult(
				ReleaseNotesLlmPort.TOOL_GET_WORK_ITEMS, Map.of("promotionId", promotionId), List.of());

		AgentDecision decision = llm.decide(context);

		assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
		var callTool = (AgentDecision.CallTool) decision;
		assertThat(callTool.toolName()).isEqualTo(ReleaseNotesLlmPort.TOOL_SUBMIT_RELEASE_NOTES);
		assertThat((String) callTool.arguments().get("draft")).contains("No linked work items found");
	}

	private AgentContext start() {
		return AgentContext.start(promotionId, applicationId, version);
	}
}
