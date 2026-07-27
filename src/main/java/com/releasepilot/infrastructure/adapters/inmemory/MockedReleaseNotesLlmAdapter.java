package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.ports.AgentContext;
import com.releasepilot.domain.ports.AgentDecision;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import com.releasepilot.domain.ports.ToolCallRecord;
import com.releasepilot.domain.ports.WorkItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory stub adapter for {@link ReleaseNotesLlmPort} (SPECS §9). A genuinely mocked LLM
 * backend: deterministic canned decisions keyed off the tool results seen so far in
 * {@link AgentContext#transcript()}, rather than a single completion — it walks the exact loop
 * SPECS §9 describes:
 *
 * <ol>
 *   <li>fetch the promotion's linked work items with {@code GetWorkItems};
 *   <li>for any work item too thin to summarize (blank description), ask a clarifying question
 *       with {@code AskClarification} — one item at a time, the "decides it needs more context"
 *       branch the spec calls out;
 *   <li>for any work item that reads as a breaking change, call {@code FlagBreakingChange};
 *   <li>draft the release notes from whatever was gathered and submit them with
 *       {@code SubmitReleaseNotes};
 *   <li>finish with {@link AgentDecision.Done}.
 * </ol>
 */
@Component
public class MockedReleaseNotesLlmAdapter implements ReleaseNotesLlmPort {

	@Override
	public AgentDecision decide(AgentContext context) {
		if (context.timesCalled(TOOL_GET_WORK_ITEMS) == 0) {
			return new AgentDecision.CallTool(TOOL_GET_WORK_ITEMS, Map.of("promotionId", context.promotionId()));
		}

		List<WorkItem> workItems = linkedWorkItems(context);

		Optional<WorkItem> needsClarification = firstUnhandled(
				workItems, context, TOOL_ASK_CLARIFICATION, item -> isBlank(item.description()));
		if (needsClarification.isPresent()) {
			WorkItem item = needsClarification.get();
			return new AgentDecision.CallTool(TOOL_ASK_CLARIFICATION, Map.of(
					"workItemId", item.id(),
					"question", "Can you clarify the scope and impact of \"" + item.title() + "\"?"));
		}

		Optional<WorkItem> needsFlag = firstUnhandled(
				workItems, context, TOOL_FLAG_BREAKING_CHANGE, MockedReleaseNotesLlmAdapter::mentionsBreakingChange);
		if (needsFlag.isPresent()) {
			WorkItem item = needsFlag.get();
			return new AgentDecision.CallTool(TOOL_FLAG_BREAKING_CHANGE, Map.of(
					"workItemId", item.id(),
					"reason", "\"" + item.title() + "\" reads as a breaking change."));
		}

		if (context.timesCalled(TOOL_SUBMIT_RELEASE_NOTES) == 0) {
			return new AgentDecision.CallTool(TOOL_SUBMIT_RELEASE_NOTES, Map.of("draft", draft(context, workItems)));
		}

		return new AgentDecision.Done(
				"Release notes drafted and submitted for promotion " + context.promotionId().value() + ".");
	}

	/** The first work item matching {@code needsHandling} that hasn't already had {@code toolName} called for it. */
	private Optional<WorkItem> firstUnhandled(
			List<WorkItem> workItems, AgentContext context, String toolName, java.util.function.Predicate<WorkItem> needsHandling) {
		Set<String> alreadyHandled = workItemIdsCalledFor(context, toolName);
		return workItems.stream()
				.filter(item -> needsHandling.test(item) && !alreadyHandled.contains(item.id()))
				.findFirst();
	}

	private Set<String> workItemIdsCalledFor(AgentContext context, String toolName) {
		return context.callsOf(toolName).stream()
				.map(call -> (String) call.arguments().get("workItemId"))
				.collect(Collectors.toSet());
	}

	@SuppressWarnings("unchecked")
	private List<WorkItem> linkedWorkItems(AgentContext context) {
		List<WorkItem> workItems = (List<WorkItem>) context.lastResultOf(TOOL_GET_WORK_ITEMS);
		return workItems == null ? List.of() : workItems;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static boolean mentionsBreakingChange(WorkItem item) {
		String haystack = (item.title() + " " + (item.description() == null ? "" : item.description())).toLowerCase();
		return haystack.contains("breaking");
	}

	private String draft(AgentContext context, List<WorkItem> workItems) {
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
				String clarification = clarificationAnswerFor(context, item.id());
				if (clarification != null) {
					draft.append("  Clarification: ").append(clarification).append("\n");
				}
			}
		}

		List<ToolCallRecord> breakingChanges = context.callsOf(TOOL_FLAG_BREAKING_CHANGE);
		if (!breakingChanges.isEmpty()) {
			draft.append("\nBreaking changes:\n");
			for (ToolCallRecord call : breakingChanges) {
				draft.append("- ").append(call.arguments().get("workItemId"))
						.append(": ").append(call.arguments().get("reason")).append("\n");
			}
		}

		return draft.toString();
	}

	private String clarificationAnswerFor(AgentContext context, String workItemId) {
		return context.callsOf(TOOL_ASK_CLARIFICATION).stream()
				.filter(call -> workItemId.equals(call.arguments().get("workItemId")))
				.map(call -> (String) call.result())
				.reduce((first, second) -> second)
				.orElse(null);
	}
}
