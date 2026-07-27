package com.releasepilot.infrastructure.queue;

import com.releasepilot.consumers.AuditLogConsumer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires {@link AuditLogConsumer} as a bean only under the {@code audit-log-consumer} profile
 * consumed by {@link AuditLogListener} — kept out of {@link AuditLogConsumer} itself so that class
 * stays exactly what its own javadoc says it is: plain Java, testable without Spring.
 *
 * <p>Also declares this consumer's dead-letter queue, bound to {@link OutboxRelay#DEAD_LETTER_EXCHANGE}
 * — {@link AuditLogListener}'s own queue routes there once a message exhausts
 * {@code spring.rabbitmq.listener.simple.retry}.
 */
@Configuration
@Profile("audit-log-consumer")
public class AuditLogConfig {

	@Bean
	public AuditLogConsumer auditLogConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		return new AuditLogConsumer(jdbcTemplate, objectMapper);
	}

	@Bean
	public DirectExchange auditLogDeadLetterExchange() {
		return new DirectExchange(OutboxRelay.DEAD_LETTER_EXCHANGE);
	}

	@Bean
	public Queue auditLogDeadLetterQueue() {
		return QueueBuilder.durable(AuditLogListener.QUEUE_NAME + ".dlq").build();
	}

	@Bean
	public Binding auditLogDeadLetterBinding(DirectExchange auditLogDeadLetterExchange, Queue auditLogDeadLetterQueue) {
		return BindingBuilder.bind(auditLogDeadLetterQueue)
				.to(auditLogDeadLetterExchange)
				.with(AuditLogListener.QUEUE_NAME);
	}
}
