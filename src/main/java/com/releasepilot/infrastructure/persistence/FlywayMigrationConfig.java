package com.releasepilot.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Runs the {@code db/migration} Flyway migrations against {@link DataSource} at startup.
 *
 * <p>This is written out explicitly rather than relying on Spring Boot's own Flyway
 * auto-configuration because, as of the Spring Boot version this project builds against,
 * {@code spring-boot-autoconfigure} no longer bundles a {@code FlywayAutoConfiguration} class —
 * adding {@code flyway-core}/{@code flyway-database-postgresql} to the classpath alone does not
 * migrate anything. Without this bean, every {@code JdbcTemplate} query against the write or
 * read-model tables fails with "relation ... does not exist", since the schema is never created.
 *
 * <p>Safe to run unconditionally from every profile (the API and every consumer process): Flyway
 * takes a database-level lock for the duration of {@code migrate()}, so concurrent callers at
 * startup serialize instead of racing, and every call after the first is a no-op once the schema
 * history table shows nothing pending.
 */
@Configuration
public class FlywayMigrationConfig {

	@Bean
	public InitializingBean flywayMigration(DataSource dataSource) {
		return () -> Flyway.configure().dataSource(dataSource).load().migrate();
	}
}
