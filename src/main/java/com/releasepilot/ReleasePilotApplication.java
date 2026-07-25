package com.releasepilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * No component in the application context depends on a live {@code DataSource} yet — command
 * handlers aren't wired to the HTTP layer, and {@code JdbcPromotionRepository} is exercised
 * directly by its own integration test rather than through Spring. Autoconfiguring one here would
 * make the default context (and every {@code @SpringBootTest}) require a reachable Postgres
 * instance, so it's excluded until something actually needs it at runtime.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class ReleasePilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReleasePilotApplication.class, args);
	}

}
