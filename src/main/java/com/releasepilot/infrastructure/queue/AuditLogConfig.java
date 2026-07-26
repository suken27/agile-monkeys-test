package com.releasepilot.infrastructure.queue;

import com.releasepilot.consumers.AuditLogConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires {@link AuditLogConsumer} as a bean only under the {@code audit-log-consumer} profile
 * consumed by {@link AuditLogListener} — kept out of {@link AuditLogConsumer} itself so that class
 * stays exactly what its own javadoc says it is: plain Java, testable without Spring.
 */
@Configuration
@Profile("audit-log-consumer")
public class AuditLogConfig {

	@Bean
	public AuditLogConsumer auditLogConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		return new AuditLogConsumer(jdbcTemplate, objectMapper);
	}
}
