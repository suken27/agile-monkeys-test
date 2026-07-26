package com.releasepilot.infrastructure.queue;

import com.releasepilot.domain.promotion.Actor;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.PromotionId;
import com.releasepilot.domain.promotion.Role;
import com.releasepilot.infrastructure.persistence.OutboxEventPublisher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link OutboxRelay} against real PostgreSQL and RabbitMQ instances (via Testcontainers):
 * proves an event written to the outbox by {@link OutboxEventPublisher} is delivered to the
 * {@value OutboxRelay#PROMOTION_EVENTS_EXCHANGE} fanout exchange, that {@code published_at} gets
 * set once delivered, and that a second relay pass does not redeliver it — the whole outbox →
 * queue path (SPECS §5.1) working end to end, not just each half in isolation.
 *
 * <p>Named {@code *IT} — see {@code JdbcPromotionRepositoryIT} for why only {@code mvnw verify} runs it.
 */
@Testcontainers
class OutboxRelayIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	@Container
	static final RabbitMQContainer RABBITMQ =
			new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;
	private static OutboxEventPublisher publisher;
	private static CachingConnectionFactory connectionFactory;
	private static RabbitTemplate rabbitTemplate;
	private static OutboxRelay relay;
	private static Queue testQueue;

	private final ApplicationId applicationId = ApplicationId.random();
	private final PromotionId promotionId = PromotionId.random();
	private final Actor approver = new Actor("bob", Role.APPROVER);

	@BeforeAll
	static void setUpInfrastructure() {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(POSTGRES.getJdbcUrl());
		config.setUsername(POSTGRES.getUsername());
		config.setPassword(POSTGRES.getPassword());
		dataSource = new HikariDataSource(config);
		Flyway.configure().dataSource(dataSource).load().migrate();
		jdbcTemplate = new JdbcTemplate(dataSource);

		ObjectMapper objectMapper = new ObjectMapper();
		publisher = new OutboxEventPublisher(jdbcTemplate, objectMapper);

		connectionFactory = new CachingConnectionFactory();
		connectionFactory.setHost(RABBITMQ.getHost());
		connectionFactory.setPort(RABBITMQ.getAmqpPort());
		connectionFactory.setUsername(RABBITMQ.getAdminUsername());
		connectionFactory.setPassword(RABBITMQ.getAdminPassword());

		rabbitTemplate = new RabbitTemplate(connectionFactory);
		relay = new OutboxRelay(jdbcTemplate, rabbitTemplate, objectMapper);

		RabbitAdmin admin = new RabbitAdmin(connectionFactory);
		FanoutExchange exchange = new FanoutExchange(OutboxRelay.PROMOTION_EVENTS_EXCHANGE);
		admin.declareExchange(exchange);
		testQueue = new Queue("test.promotion-events." + UUID.randomUUID(), false, false, true);
		admin.declareQueue(testQueue);
		Binding binding = BindingBuilder.bind(testQueue).to(exchange);
		admin.declareBinding(binding);
	}

	@AfterAll
	static void tearDown() {
		connectionFactory.destroy();
		dataSource.close();
	}

	@Test
	void relayingPendingEventsDeliversThemToTheFanoutExchangeAndMarksThemSent() {
		DomainEvent event = DomainEvent.of(
				"PromotionApproved", promotionId, applicationId, approver, Map.of("approvedBy", "bob"));
		publisher.publish(event);

		int relayed = relay.relayPendingEvents();

		assertThat(relayed).isEqualTo(1);

		Message received = rabbitTemplate.receive(testQueue.getName(), 5000);
		assertThat(received).isNotNull();
		String body = new String(received.getBody(), StandardCharsets.UTF_8);
		assertThat(body)
				.contains("\"eventType\":\"PromotionApproved\"")
				.contains(promotionId.value().toString())
				.contains("\"approvedBy\":\"bob\"");

		Boolean stillUnpublished = jdbcTemplate.queryForObject(
				"SELECT published_at IS NULL FROM outbox_events WHERE id = ?", Boolean.class, event.eventId());
		assertThat(stillUnpublished).isFalse();
	}

	@Test
	void relayingAgainWithNothingPendingDeliversNothing() {
		int relayed = relay.relayPendingEvents();

		assertThat(relayed).isZero();
		assertThat(rabbitTemplate.receive(testQueue.getName(), 500)).isNull();
	}
}
