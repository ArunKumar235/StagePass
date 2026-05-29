package org.stagepass.bookingservice.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * KAFKA CONFIG
 *
 * Configures producer and consumer for the Booking Service.
 *
 * PRODUCER — publishes:
 * booking-confirmed → consumed by Event Service (mark seats BOOKED)
 * → consumed by Notification Service (send ticket)
 * booking-failed → consumed by Event Service (release seats)
 * booking-cancelled → consumed by Event Service (release seats)
 * → consumed by Notification Service (cancellation email)
 *
 * CONSUMER — listens to:
 * event-cancelled → auto-cancel all bookings for the cancelled event
 *
 * DEAD LETTER TOPIC (DLT):
 * If a consumed message fails after max retries, it goes to
 * {topic}.DLT (e.g. event-cancelled.DLT) instead of being silently dropped.
 * This ensures no message is ever lost — the DLT can be monitored and
 * replayed once the underlying issue is fixed.
 *
 * MANUAL ACKNOWLEDGMENT:
 * ack-mode = MANUAL_IMMEDIATE — Kafka offset is committed only AFTER
 * the DB update succeeds. If the DB call fails, Kafka redelivers the message.
 * This is the at-least-once delivery guarantee that makes the consumer
 * idempotency logic in EventCancellationConsumer necessary.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:booking-service-group}")
    private String groupId;

    // ── PRODUCER ─────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        // acks=all: wait for all in-sync replicas to acknowledge
        // ensures no booking event is lost even if a broker fails
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);

        // Idempotent producer: exactly-once delivery (requires acks=all)
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);

        // Don't add Java class type headers — keeps payloads clean for consumers
        config.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── CONSUMER ─────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);

        // Start from earliest offset if no committed offset exists
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Disable auto-commit — we use MANUAL_IMMEDIATE acknowledgment
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Process up to 10 messages per poll — conservative for DB-heavy consumers
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);

        // Trust our own packages for deserialization
        config.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "org.stagepass.*,java.util");
        config.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, Map.class.getName());

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Listener container factory with:
     * - MANUAL_IMMEDIATE ack mode
     * - Dead Letter Topic (DLT) on max retry exhaustion
     * - Fixed backoff: 3 retries with 2s interval
     *
     * Named "manualAckListenerContainerFactory" to match the
     * containerFactory attribute in @KafkaListener annotations.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> manualAckListenerContainerFactory() {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());

        // MANUAL_IMMEDIATE: only commit offset after acknowledgment.acknowledge() is
        // called
        factory.getContainerProperties().setAckMode(
                ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // 3 consumer threads — one per partition (matches default topic partition
        // count)
        factory.setConcurrency(3);

        // Error handler: retry 3 times with 2s between attempts
        // After exhaustion → publish to Dead Letter Topic instead of silently dropping
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(2000L, 3L) // 2s interval, 3 retries
        );

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
