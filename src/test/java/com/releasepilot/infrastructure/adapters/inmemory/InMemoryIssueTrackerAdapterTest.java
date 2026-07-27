package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.ports.WorkItem;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIssueTrackerAdapterTest {

	private final InMemoryIssueTrackerAdapter adapter = new InMemoryIssueTrackerAdapter();

	@Test
	void returnsAnEmptyListWhenNothingHasBeenSeeded() {
		assertThat(adapter.getLinkedWorkItems(ApplicationId.random(), new Version("1.0.0"))).isEmpty();
	}

	@Test
	void returnsTheSeededWorkItemsForTheExactApplicationAndVersion() {
		ApplicationId applicationId = ApplicationId.random();
		Version version = new Version("1.0.0");
		WorkItem workItem = new WorkItem("TICKET-1", "Fix the thing", "https://tracker.example/TICKET-1", "Fixed it.");
		adapter.seed(applicationId, version, List.of(workItem));

		assertThat(adapter.getLinkedWorkItems(applicationId, version)).containsExactly(workItem);
	}

	@Test
	void doesNotReturnWorkItemsSeededForADifferentVersion() {
		ApplicationId applicationId = ApplicationId.random();
		adapter.seed(applicationId, new Version("1.0.0"), List.of(new WorkItem("TICKET-1", "Fix", "url", "desc")));

		assertThat(adapter.getLinkedWorkItems(applicationId, new Version("2.0.0"))).isEmpty();
	}

	@Test
	void answerClarificationReturnsAGenericAnswerWhenNothingHasBeenSeeded() {
		assertThat(adapter.answerClarification("TICKET-1", "What's the impact?"))
				.isEqualTo("No additional details available for TICKET-1.");
	}

	@Test
	void answerClarificationReturnsTheSeededAnswer() {
		adapter.seedClarificationAnswer("TICKET-1", "Only affects the admin dashboard.");

		assertThat(adapter.answerClarification("TICKET-1", "What's the impact?"))
				.isEqualTo("Only affects the admin dashboard.");
	}
}
