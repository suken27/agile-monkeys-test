package com.releasepilot.domain.ports;

/** A work item (ticket) linked to an application version, as returned by {@link IssueTrackerPort}. */
public record WorkItem(String id, String title, String url) {
}
