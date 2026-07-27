package com.releasepilot.domain.ports;

/**
 * A work item (ticket) linked to an application version, as returned by {@link IssueTrackerPort}.
 * {@code description} may be {@code null}/blank — a ticket without one is too thin for the
 * release-notes agent to summarize on its own, and is exactly what its {@code AskClarification}
 * tool (SPECS §9) exists to fill in.
 */
public record WorkItem(String id, String title, String url, String description) {
}
