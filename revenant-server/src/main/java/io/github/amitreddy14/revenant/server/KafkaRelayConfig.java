package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.persistence.OutboxRelay;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the outbox relay to Kafka. Switch it off with {@code revenant.kafka.enabled=false}: the
 * engine works without Kafka, events then simply wait in the outbox (ADR-0005).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "revenant.kafka.enabled", havingValue = "true", matchIfMissing = true)
class KafkaRelayConfig {

    @Bean(destroyMethod = "close")
    KafkaEventSink kafkaEventSink(
            @Value("${revenant.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${revenant.kafka.topic:revenant.workflow-events}") String topic) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // No duplicates from producer-side retries, and no reordering between them.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // Fail fast when Kafka is down: the relay keeps the rows and tries again on the next tick.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        return new KafkaEventSink(new KafkaProducer<String, String>(config), topic);
    }

    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, KafkaEventSink sink) {
        return new OutboxRelay(dataSource, sink);
    }
}
