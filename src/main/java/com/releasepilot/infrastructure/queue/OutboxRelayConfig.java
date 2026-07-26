package com.releasepilot.infrastructure.queue;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires {@link OutboxRelay} as a bean only under the {@code outbox-relay} profile consumed by
 * {@link OutboxRelayScheduler} — kept out of {@link OutboxRelay} itself so that class stays exactly
 * what its own javadoc says it is: plain Java, testable without Spring.
 */
@Configuration
@Profile("outbox-relay")
@EnableScheduling
public class OutboxRelayConfig {

	@Bean
	public OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
		return new OutboxRelay(jdbcTemplate, rabbitTemplate, objectMapper);
	}
}
