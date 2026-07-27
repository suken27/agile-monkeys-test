package com.releasepilot.infrastructure.queue;

import com.releasepilot.application.queries.PromotionReadModelPort;
import com.releasepilot.consumers.ReleaseNotesAgentConsumer;
import com.releasepilot.domain.ports.IssueTrackerPort;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires {@link ReleaseNotesAgentConsumer} as a bean only under the
 * {@code release-notes-agent-consumer} profile consumed by {@link ReleaseNotesAgentListener} — kept
 * out of {@link ReleaseNotesAgentConsumer} itself so that class stays exactly what its own javadoc
 * says it is: plain Java, testable without Spring.
 *
 * <p>Also declares this consumer's dead-letter queue, bound to {@link OutboxRelay#DEAD_LETTER_EXCHANGE}
 * — {@link ReleaseNotesAgentListener}'s own queue routes there once a message exhausts
 * {@code spring.rabbitmq.listener.simple.retry}.
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

	@Bean
	public DirectExchange releaseNotesAgentDeadLetterExchange() {
		return new DirectExchange(OutboxRelay.DEAD_LETTER_EXCHANGE);
	}

	@Bean
	public Queue releaseNotesAgentDeadLetterQueue() {
		return QueueBuilder.durable(ReleaseNotesAgentListener.QUEUE_NAME + ".dlq").build();
	}

	@Bean
	public Binding releaseNotesAgentDeadLetterBinding(
			DirectExchange releaseNotesAgentDeadLetterExchange, Queue releaseNotesAgentDeadLetterQueue) {
		return BindingBuilder.bind(releaseNotesAgentDeadLetterQueue)
				.to(releaseNotesAgentDeadLetterExchange)
				.with(ReleaseNotesAgentListener.QUEUE_NAME);
	}
}
