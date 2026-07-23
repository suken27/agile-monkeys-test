package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Version;

import java.util.List;

/**
 * Output port: a capability the domain requires of the outside world to fetch the work items
 * linked to an application version (e.g. for the promotion detail query or the release-notes
 * agent). Implemented by an infrastructure adapter.
 */
public interface IssueTrackerPort {

	List<WorkItem> getLinkedWorkItems(ApplicationId applicationId, Version version);
}
