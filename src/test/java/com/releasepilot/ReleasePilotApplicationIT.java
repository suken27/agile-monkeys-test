package com.releasepilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Smoke-tests that the full Spring context wires together: every {@code @Repository}/{@code
 * @Service}/{@code @Component}/{@code @RestController} bean added for the HTTP command and query
 * layers (SPECS §10) actually satisfies its constructor dependencies, backed by a real, disposable
 * PostgreSQL instance (via Testcontainers) so Flyway can migrate the schema those beans need.
 *
 * <p>Named {@code *IT} — see {@code JdbcPromotionRepositoryIT} for why only {@code mvnw verify}
 * runs it; the plain unit-test suite (SPECS §14, {@code mvnw test}) still needs no database.
 */
@Testcontainers
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class ReleasePilotApplicationIT {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Test
	void contextLoads() {
	}

}
