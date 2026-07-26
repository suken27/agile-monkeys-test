package com.releasepilot.domain.ports;

/**
 * Output port: the LLM backend driving the release-notes agent's tool-calling loop (SPECS §9).
 * Given the conversation so far, decides the next action — call a named tool, or finish with a
 * final message — the same shape a real chat-completions API with tool-calling would return.
 * Implemented by a mocked, deterministic adapter for this exercise (no real LLM API key required),
 * but {@code ReleaseNotesAgentConsumer} round-trips through this port exactly as it would a real
 * one: context in, one decision out, repeated until it returns {@link AgentDecision.Done}.
 */
public interface ReleaseNotesLlmPort {

	String TOOL_GET_LINKED_WORK_ITEMS = "get_linked_work_items";
	String TOOL_GET_PROMOTION_HISTORY = "get_promotion_history";
	String TOOL_SAVE_RELEASE_NOTES = "save_release_notes";

	AgentDecision decide(AgentContext context);
}
