package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.ports.IssueTrackerPort;
import com.releasepilot.domain.ports.WorkItem;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Version;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub adapter for {@link IssueTrackerPort} (SPECS §6). Returns a canned/seeded list of
 * fake work items keyed by (applicationId, version) — there is no outbound HTTP in this exercise;
 * a real issue-tracker client would implement the same port. Callers that haven't seeded anything
 * for a given pair get an empty list, so the "no linked tickets" path is exercisable too.
 */
@Component
public class InMemoryIssueTrackerAdapter implements IssueTrackerPort {

	private final Map<String, List<WorkItem>> linkedWorkItems = new ConcurrentHashMap<>();

	@Override
	public List<WorkItem> getLinkedWorkItems(ApplicationId applicationId, Version version) {
		return linkedWorkItems.getOrDefault(key(applicationId, version), List.of());
	}

	/** Seeds the fake work items to return for a given (applicationId, version) pair. */
	public void seed(ApplicationId applicationId, Version version, List<WorkItem> workItems) {
		linkedWorkItems.put(key(applicationId, version), List.copyOf(workItems));
	}

	private static String key(ApplicationId applicationId, Version version) {
		return applicationId.value() + "@" + version.value();
	}
}
