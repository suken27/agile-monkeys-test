package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Version;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

	public AgentContext withToolResult(String toolName, Map<String, Object> arguments, Object result) {
		List<ToolCallRecord> appended = new ArrayList<>(transcript);
		appended.add(new ToolCallRecord(toolName, arguments, result));
		return new AgentContext(goal, promotionId, applicationId, version, List.copyOf(appended));
	}

	/** How many times {@code toolName} has already been called. */
	public long timesCalled(String toolName) {
		return callsOf(toolName).size();
	}

	/** The most recent result of {@code toolName}, or {@code null} if it hasn't been called yet. */
	public Object lastResultOf(String toolName) {
		List<ToolCallRecord> calls = callsOf(toolName);
		return calls.isEmpty() ? null : calls.get(calls.size() - 1).result();
	}

	/** Every past call to {@code toolName}, in call order — lets callers inspect what each one concerned. */
	public List<ToolCallRecord> callsOf(String toolName) {
		return transcript.stream().filter(call -> call.toolName().equals(toolName)).toList();
	}
}
