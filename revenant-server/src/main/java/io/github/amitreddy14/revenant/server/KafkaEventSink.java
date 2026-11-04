package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.persistence.EventSink;
import io.github.amitreddy14.revenant.persistence.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Publishes outbox events to a Kafka topic (ADR-0005).
 *
 * <p>The message key is the workflow id, so one workflow's events land in one partition, in order.
 * The value is the event payload. The headers {@code event_id}, {@code event_type} and
 * {@code created_at} carry the rest; consumers drop duplicates by {@code event_id}, because
 * delivery is at least once. Each send waits for the broker's acknowledgement.
 */
final class KafkaEventSink implements EventSink, AutoCloseable {

    static final String HEADER_EVENT_ID = "event_id";
    static final String HEADER_EVENT_TYPE = "event_type";
    static final String HEADER_CREATED_AT = "created_at";

    private static final long SEND_TIMEOUT_SECONDS = 15;

    private final Producer<String, String> producer;
    private final String topic;

    KafkaEventSink(Producer<String, String> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, event.workflowId().toString(), event.payloadJson());
        record.headers().add(HEADER_EVENT_ID, utf8(Long.toString(event.eventId())));
        record.headers().add(HEADER_EVENT_TYPE, utf8(event.eventType()));
        record.headers().add(HEADER_CREATED_AT, utf8(event.createdAt().toString()));
        producer.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        producer.close();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
