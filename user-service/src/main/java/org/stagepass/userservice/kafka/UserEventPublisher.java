package org.stagepass.userservice.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.stagepass.userservice.entity.User;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class UserEventPublisher {

	private static final String TOPIC_USER_REGISTERED = "user-registered";

	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Autowired
	public UserEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	/**
	 * Publishes a user registration event to Kafka when a user successfully registers.
	 * The userId is used as the partition key to ensure all events for the same user
	 * go to the same partition for ordering guarantees.
	 *
	 * @param user the newly registered user entity
	 */
	public void publishUserRegistered(User user) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("userId", user.getId().toString());
		payload.put("email", user.getEmail());
		payload.put("username", user.getUsername());
		payload.put("occurredAt", Instant.now().toString());

		send(TOPIC_USER_REGISTERED, user.getId().toString(), payload);
	}

	/**
	 * Sends a Kafka message asynchronously.
	 *
	 * Uses CompletableFuture callback to log success or failure.
	 * Failure is logged but not thrown — Kafka has its own retry mechanism.
	 */
	private void send(String topic, String key, Object payload) {
		CompletableFuture<SendResult<String, Object>> future =
				kafkaTemplate.send(topic, key, payload);

		future.whenComplete((result, ex) -> {
			if (ex != null) {
				log.error("Failed to publish Kafka event: topic={} key={} error={}",
						topic, key, ex.getMessage());
			} else {
				log.debug("Kafka event published: topic={} key={} partition={} offset={}",
						topic, key,
						result.getRecordMetadata().partition(),
						result.getRecordMetadata().offset());
			}
		});
	}
}
