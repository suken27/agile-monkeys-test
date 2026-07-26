package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * The release-notes agent's running conversation state (SPECS §9): the fixed goal it was given up
 * front, the promotion/application/version it concerns, and the transcript of tool calls made so
 * far. Appended to after every tool execution and handed back to {@link ReleaseNotesLlmPort#decide}
 * for the next decision — the same round-trip shape a real prompt/tool-result/next-call loop would
 * have, just with a mocked model on the other end.
 */
public record AgentContext(
		String goal,
		PromotionId promotionId,
		ApplicationId applicationId,
		Version version,
		List<ToolCallRecord> transcript) {

	public static AgentContext start(PromotionId promotionId, ApplicationId applicationId, Version version) {
		return new AgentContext(
				"Draft release notes for promotion " + promotionId.value() + ".",
				promotionId, applicationId, version, List.of());
	}

	public AgentContext withToolResult(String toolName, Object result) {
		List<ToolCallRecord> appended = new ArrayList<>(transcript);
		appended.add(new ToolCallRecord(toolName, result));
		return new AgentContext(goal, promotionId, applicationId, version, List.copyOf(appended));
	}

	/** How many times {@code toolName} has already been called — lets the mocked LLM decide to re-fetch. */
	public long timesCalled(String toolName) {
		return transcript.stream().filter(call -> call.toolName().equals(toolName)).count();
	}

	/** The most recent result of {@code toolName}, or {@code null} if it hasn't been called yet. */
	public Object lastResultOf(String toolName) {
		for (int i = transcript.size() - 1; i >= 0; i--) {
			if (transcript.get(i).toolName().equals(toolName)) {
				return transcript.get(i).result();
			}
		}
		return null;
	}
}
