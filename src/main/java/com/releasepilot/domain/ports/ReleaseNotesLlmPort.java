package com.releasepilot.domain.ports;

/**
 * Output port: the LLM backend driving the release-notes agent's tool-calling loop (SPECS §9).
 * Given the conversation so far, decides the next action — call a named tool, or finish with a
 * final message — the same shape a real chat-completions API with tool-calling would return.
 * Implemented by a mocked, deterministic adapter for this exercise (no real LLM API key required),
 * but {@code ReleaseNotesAgentConsumer} round-trips through this port exactly as it would a real
 * one: context in, one decision out, repeated until it returns {@link AgentDecision.Done}.
 *
 * <p>The agent is given exactly the four tools SPECS §9 names: {@code GetWorkItems},
 * {@code AskClarification}, {@code FlagBreakingChange}, and {@code SubmitReleaseNotes}.
 */
public interface ReleaseNotesLlmPort {

	String TOOL_GET_WORK_ITEMS = "get_work_items";
	String TOOL_ASK_CLARIFICATION = "ask_clarification";
	String TOOL_FLAG_BREAKING_CHANGE = "flag_breaking_change";
	String TOOL_SUBMIT_RELEASE_NOTES = "submit_release_notes";

	AgentDecision decide(AgentContext context);
}
