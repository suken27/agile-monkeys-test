package com.releasepilot.infrastructure.queue;

import com.releasepilot.consumers.ReadModelProjector;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.PromotionId;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The thin queue-transport adapter {@link ReadModelProjector}'s class-level javadoc anticipates:
 * declares and binds its own queue to the {@value OutboxRelay#PROMOTION_EVENTS_EXCHANGE} fanout
 * exchange (SPECS §8.2), decodes the JSON envelope {@link OutboxRelay} publishes back into a
 * {@link DomainEvent}, and hands it to {@link ReadModelProjector#project}.
 *
 * <p>Only active under the {@code read-model-projector} Spring profile (see {@code
 * ReadModelProjectorConfig} and {@code application.yml}), so this consumer is deployed and scaled
 * as its own OS process/container — started from the same jar/image as the API via {@code
 * SPRING_PROFILES_ACTIVE=read-model-projector} — rather than sharing the API process's call stack
 * (SPECS §8, §15).
 */
@Component
@Profile("read-model-projector")
public class ReadModelProjectorListener {

	static final String QUEUE_NAME = "promotion.events.read-model-projector";

	private final ReadModelProjector projector;
	private final ObjectMapper objectMapper;

	public ReadModelProjectorListener(ReadModelProjector projector, ObjectMapper objectMapper) {
		this.projector = projector;
		this.objectMapper = objectMapper;
	}

	@RabbitListener(bindings = @QueueBinding(
			value = @Queue(name = QUEUE_NAME, durable = "true"),
			exchange = @Exchange(name = OutboxRelay.PROMOTION_EVENTS_EXCHANGE, type = ExchangeTypes.FANOUT)))
	public void onMessage(byte[] body) {
		Map<String, Object> envelope = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
		});
		projector.project(toDomainEvent(envelope));
	}

	@SuppressWarnings("unchecked")
	private static DomainEvent toDomainEvent(Map<String, Object> envelope) {
		return new DomainEvent(
				UUID.fromString((String) envelope.get("eventId")),
				(String) envelope.get("eventType"),
				Instant.parse((String) envelope.get("occurredAt")),
				PromotionId.of((String) envelope.get("promotionId")),
				ApplicationId.of((String) envelope.get("applicationId")),
				(String) envelope.get("actingUser"),
				(Map<String, Object>) envelope.get("payload"));
	}
}
