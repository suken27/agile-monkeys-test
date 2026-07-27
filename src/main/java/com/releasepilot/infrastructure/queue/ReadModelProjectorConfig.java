package com.releasepilot.infrastructure.queue;

import com.releasepilot.consumers.ReadModelProjector;
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
 * Wires {@link ReadModelProjector} as a bean only under the {@code read-model-projector} profile
 * consumed by {@link ReadModelProjectorListener} — kept out of {@link ReadModelProjector} itself so
 * that class stays exactly what its own javadoc says it is: plain Java, testable without Spring.
 *
 * <p>Also declares this consumer's dead-letter queue, bound to {@link OutboxRelay#DEAD_LETTER_EXCHANGE}
 * — {@link ReadModelProjectorListener}'s own queue routes there once a message exhausts
 * {@code spring.rabbitmq.listener.simple.retry}.
 */
@Configuration
@Profile("read-model-projector")
public class ReadModelProjectorConfig {

	@Bean
	public ReadModelProjector readModelProjector(JdbcTemplate jdbcTemplate) {
		return new ReadModelProjector(jdbcTemplate);
	}

	@Bean
	public DirectExchange readModelProjectorDeadLetterExchange() {
		return new DirectExchange(OutboxRelay.DEAD_LETTER_EXCHANGE);
	}

	@Bean
	public Queue readModelProjectorDeadLetterQueue() {
		return QueueBuilder.durable(ReadModelProjectorListener.QUEUE_NAME + ".dlq").build();
	}

	@Bean
	public Binding readModelProjectorDeadLetterBinding(
			DirectExchange readModelProjectorDeadLetterExchange, Queue readModelProjectorDeadLetterQueue) {
		return BindingBuilder.bind(readModelProjectorDeadLetterQueue)
				.to(readModelProjectorDeadLetterExchange)
				.with(ReadModelProjectorListener.QUEUE_NAME);
	}
}
