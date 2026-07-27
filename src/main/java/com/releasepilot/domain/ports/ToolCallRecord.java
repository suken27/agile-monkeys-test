package com.releasepilot.domain.ports;

import java.util.Map;

/**
 * One executed tool call — the arguments it was invoked with and the result it returned — appended
 * to {@link AgentContext#transcript()}. The arguments are kept (not just the result) because tools
 * like {@code AskClarification}/{@code FlagBreakingChange} are scoped to a single work item, and the
 * mocked LLM needs to tell which work item a past call concerned, not just what it returned.
 */
public record ToolCallRecord(String toolName, Map<String, Object> arguments, Object result) {
}
