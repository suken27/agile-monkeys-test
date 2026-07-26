package com.releasepilot.domain.ports;

/** One executed tool call and the result it returned, appended to {@link AgentContext#transcript()}. */
public record ToolCallRecord(String toolName, Object result) {
}
