package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.application.queries.PromotionHistoryPage.PromotionHistoryItem;
import com.releasepilot.domain.ports.AgentContext;
import com.releasepilot.domain.ports.AgentDecision;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import com.releasepilot.domain.ports.WorkItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * In-memory stub adapter for {@link ReleaseNotesLlmPort} (SPECS §9). A genuinely mocked LLM
 * backend: deterministic canned decisions keyed off the tool results seen so far in
 * {@link AgentContext#transcript()}, rather than a single completion — it walks the exact loop
 * SPECS §9 describes:
 *
 * <ol>
 *   <li>fetch linked work items;
 *   <li>if that first fetch came back empty, re-fetch once — the "decides it needs another tool
 *       call" branch the spec calls out — before giving up and drafting anyway;
 *   <li>fetch recent promotion history for "since last release" framing;
 *   <li>draft the release notes from whatever tool results were gathered and save them;
 *   <li>finish with {@link AgentDecision.Done}.
 * </ol>
 */
@Component
public class MockedReleaseNotesLlmAdapter implements ReleaseNotesLlmPort {

	@Override
	public AgentDecision decide(AgentContext context) {
		long workItemFetches = context.timesCalled(TOOL_GET_LINKED_WORK_ITEMS);

		if (workItemFetches == 0) {
			return getLinkedWorkItems(context);
		}
		if (workItemFetches == 1 && linkedWorkItems(context).isEmpty()) {
			return getLinkedWorkItems(context);
		}
		if (context.timesCalled(TOOL_GET_PROMOTION_HISTORY) == 0) {
			return new AgentDecision.CallTool(TOOL_GET_PROMOTION_HISTORY, Map.of("applicationId", context.applicationId()));
		}
		if (context.timesCalled(TOOL_SAVE_RELEASE_NOTES) == 0) {
			return new AgentDecision.CallTool(
					TOOL_SAVE_RELEASE_NOTES,
					Map.of("promotionId", context.promotionId(), "draftText", draft(context)));
		}
		return new AgentDecision.Done("Release notes drafted and saved for promotion " + context.promotionId().value() + ".");
	}

	private AgentDecision getLinkedWorkItems(AgentContext context) {
		return new AgentDecision.CallTool(
				TOOL_GET_LINKED_WORK_ITEMS,
				Map.of("applicationId", context.applicationId(), "version", context.version()));
	}

	@SuppressWarnings("unchecked")
	private List<WorkItem> linkedWorkItems(AgentContext context) {
		List<WorkItem> workItems = (List<WorkItem>) context.lastResultOf(TOOL_GET_LINKED_WORK_ITEMS);
		return workItems == null ? List.of() : workItems;
	}

	@SuppressWarnings("unchecked")
	private List<PromotionHistoryItem> promotionHistory(AgentContext context) {
		List<PromotionHistoryItem> history = (List<PromotionHistoryItem>) context.lastResultOf(TOOL_GET_PROMOTION_HISTORY);
		return history == null ? List.of() : history;
	}

	private String draft(AgentContext context) {
		List<WorkItem> workItems = linkedWorkItems(context);
		List<PromotionHistoryItem> history = promotionHistory(context);

		StringBuilder draft = new StringBuilder()
				.append("Release notes for ").append(context.applicationId().value())
				.append(" version ").append(context.version().value()).append("\n\n");

		if (workItems.isEmpty()) {
			draft.append("No linked work items found for this release.\n");
		} else {
			draft.append("Included work items:\n");
			for (WorkItem item : workItems) {
				draft.append("- ").append(item.id()).append(": ").append(item.title())
						.append(" (").append(item.url()).append(")\n");
			}
		}

		if (!history.isEmpty()) {
			draft.append("\nSince the last release:\n");
			for (PromotionHistoryItem entry : history) {
				draft.append("- ").append(entry.version().value())
						.append(" -> ").append(entry.targetEnvironment())
						.append(" [").append(entry.status()).append("]\n");
			}
		}

		return draft.toString();
	}
}
