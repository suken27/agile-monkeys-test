package com.releasepilot.domain.ports;

import java.util.Map;

/**
 * The release-notes agent LLM's decision for one loop iteration (SPECS §9): either call a named
 * tool with arguments, or finish the loop with a final message instead of another tool call.
 */
public sealed interface AgentDecision {

	record CallTool(String toolName, Map<String, Object> arguments) implements AgentDecision {
	}

	record Done(String finalMessage) implements AgentDecision {
	}
}
