package com.releasepilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	/**
	 * Guards against the schema silently never being created: {@code contextLoads()} alone would
	 * still pass even if nothing ran the {@code db/migration} scripts against {@link #POSTGRES},
	 * since a missing {@code FlywayMigrationConfig}/equivalent bean doesn't fail context startup —
	 * it just leaves every table absent until the first real query blows up with "relation ...
	 * does not exist" (as happened before {@code FlywayMigrationConfig} was added: Spring Boot's
	 * own Flyway auto-configuration is absent from this version's {@code spring-boot-autoconfigure},
	 * so adding {@code flyway-core} to the classpath alone migrates nothing). Asserting against
	 * {@code flyway_schema_history} directly proves migrations actually ran, independent of any
	 * one table's name.
	 */
	@Test
	void flywayMigratesEveryVersionAgainstTheRealDatabase() {
		List<String> appliedVersions = jdbcTemplate.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
				String.class);

		assertThat(appliedVersions).containsExactly("1", "2", "3", "4", "5", "6");

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM promotions", Integer.class)).isZero();
	}

}
