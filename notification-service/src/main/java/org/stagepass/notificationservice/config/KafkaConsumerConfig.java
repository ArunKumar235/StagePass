package org.stagepass.notificationservice.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * KAFKA CONSUMER CONFIG
 *
 * Configures the consumer factory shared across all 5 Kafka listeners
 * in the notification service.
 *
 * KEY DESIGN DECISIONS:
 *
 * 1. MANUAL_IMMEDIATE ack mode
 * Kafka offset committed only AFTER the email is sent successfully.
 * If SMTP fails, the listener throws NotificationException without
 * acknowledging — Kafka redelivers the message for retry.
 * This guarantees no notification is silently dropped due to transient
 * SMTP or PDF generation failures.
 *
 * 2. Dead Letter Topic (DLT)
 * After 3 retries with 2s intervals, the message is published to
 * {topic}.DLT (e.g. booking-confirmed.DLT).
 * Operations team monitors DLT for permanently failing messages.
 * DLT prevents the consumer from being stuck forever on a bad payload.
 *
 * 3. Concurrency = 2
 * 2 consumer threads per listener. Enough for email throughput.
 * Avoid setting too high — SMTP servers rate-limit connections
 * and the notification_logs table has idempotency checks on every send.
 *
 * 4. max-poll-records = 5
 * Conservative — each poll result requires an SMTP call per message.
 * SMTP calls are slow (~200-500ms). Processing 5 at a time prevents
 * Kafka session timeout during heavy processing.
 */
@Configuration
public class KafkaConsumerConfig {

        @Value("${spring.kafka.bootstrap-servers}")
        private String bootstrapServers;

        @Value("${spring.kafka.consumer.group-id}")
        private String groupId;

        @Bean
        public ConsumerFactory<String, Object> consumerFactory() {
                Map<String, Object> props = new HashMap<>();
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
                props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
                props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
                props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
                props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);

                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

                // Conservative poll size — each message needs an SMTP call
                props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5);

                // Trust our own packages + java.util for Map deserialization
                props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "org.stagepass.*,java.util");
                props.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
                props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, Map.class.getName());

                return new DefaultKafkaConsumerFactory<>(props);
        }

        /**
         * Listener container factory with manual ack + DLT error handler.
         * All @KafkaListener methods in this service reference this factory:
         * containerFactory = "manualAckListenerContainerFactory"
         */
        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> manualAckListenerContainerFactory(
                        ConsumerFactory<String, Object> consumerFactory,
                        KafkaTemplate<String, Object> kafkaTemplate) {

                ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();

                factory.setConsumerFactory(consumerFactory);

                // Manual ack — offset committed only after successful processing
                factory.getContainerProperties()
                                .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

                // 2 concurrent consumer threads per listener
                factory.setConcurrency(2);

                // Retry 3 times with 2-second gaps, then route to DLT
                // FixedBackOff(interval_ms, max_attempts)
                DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

                DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                                recoverer,
                                new FixedBackOff(2000L, 3L));

                // Do not retry on these exceptions — they indicate bad payload, not transient
                // errors
                errorHandler.addNotRetryableExceptions(
                                JsonParseException.class,
                                MismatchedInputException.class);

                factory.setCommonErrorHandler(errorHandler);

                return factory;
        }
}