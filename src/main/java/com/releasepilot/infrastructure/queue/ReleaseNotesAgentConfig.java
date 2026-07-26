package com.releasepilot.infrastructure.queue;

import com.releasepilot.application.queries.PromotionReadModelPort;
import com.releasepilot.consumers.ReleaseNotesAgentConsumer;
import com.releasepilot.domain.ports.IssueTrackerPort;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires {@link ReleaseNotesAgentConsumer} as a bean only under the
 * {@code release-notes-agent-consumer} profile consumed by {@link ReleaseNotesAgentListener} — kept
 * out of {@link ReleaseNotesAgentConsumer} itself so that class stays exactly what its own javadoc
 * says it is: plain Java, testable without Spring.
 */
@Configuration
@Profile("release-notes-agent-consumer")
public class ReleaseNotesAgentConfig {

	@Bean
	public ReleaseNotesAgentConsumer releaseNotesAgentConsumer(
			ReleaseNotesLlmPort llm, IssueTrackerPort issueTracker, PromotionReadModelPort readModel,
			JdbcTemplate jdbcTemplate) {
		return new ReleaseNotesAgentConsumer(llm, issueTracker, readModel, jdbcTemplate);
	}
}
