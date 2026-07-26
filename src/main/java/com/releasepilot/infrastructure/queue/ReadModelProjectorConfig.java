package com.releasepilot.infrastructure.queue;

import com.releasepilot.consumers.ReadModelProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires {@link ReadModelProjector} as a bean only under the {@code read-model-projector} profile
 * consumed by {@link ReadModelProjectorListener} — kept out of {@link ReadModelProjector} itself so
 * that class stays exactly what its own javadoc says it is: plain Java, testable without Spring.
 */
@Configuration
@Profile("read-model-projector")
public class ReadModelProjectorConfig {

	@Bean
	public ReadModelProjector readModelProjector(JdbcTemplate jdbcTemplate) {
		return new ReadModelProjector(jdbcTemplate);
	}
}
