package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.persistence.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

/** The Kafka message format, checked against a small in-memory producer (no broker needed). */
class KafkaEventSinkTest {

    private static final Instant CREATED = Instant.parse("2026-11-05T10:15:30Z");

    private final FakeProducer producer = new FakeProducer();
    private final KafkaEventSink sink = new KafkaEventSink(producer.proxy, "revenant.workflow-events");

    @Test
    void sendsTheEventToTheTopicKeyedByWorkflowId() throws Exception {
        WorkflowId id = WorkflowId.random();

        sink.publish(new OutboxEvent(17, id, "ActivityCompleted", "{\"result\": 42}", CREATED));

        assertThat(producer.sent).hasSize(1);
        ProducerRecord<String, String> record = producer.sent.get(0);
        assertThat(record.topic()).isEqualTo("revenant.workflow-events");
        assertThat(record.key()).isEqualTo(id.toString());
        assertThat(record.value()).isEqualTo("{\"result\": 42}");
    }

    @Test
    void putsEventIdTypeAndTimeInHeadersSoConsumersCanDeduplicate() throws Exception {
        sink.publish(new OutboxEvent(17, WorkflowId.random(), "ActivityCompleted", "{}", CREATED));

        ProducerRecord<String, String> record = producer.sent.get(0);
        assertThat(header(record, "event_id")).isEqualTo("17");
        assertThat(header(record, "event_type")).isEqualTo("ActivityCompleted");
        assertThat(header(record, "created_at")).isEqualTo("2026-11-05T10:15:30Z");
    }

    @Test
    void oneWorkflowAlwaysUsesTheSameKeySoItsEventsStayInOnePartition() throws Exception {
        WorkflowId id = WorkflowId.random();

        sink.publish(new OutboxEvent(1, id, "WorkflowStarted", "{}", CREATED));
        sink.publish(new OutboxEvent(2, id, "ActivityScheduled", "{}", CREATED));

        assertThat(producer.sent.get(0).key()).isEqualTo(producer.sent.get(1).key());
        assertThat(header(producer.sent.get(0), "event_id")).isEqualTo("1");
        assertThat(header(producer.sent.get(1), "event_id")).isEqualTo("2");
    }

    @Test
    void aSendThatTheBrokerRejectsIsReportedSoTheEventStaysInTheOutbox() {
        producer.failNext = new RuntimeException("broker is down");

        assertThatThrownBy(() -> sink.publish(new OutboxEvent(1, WorkflowId.random(), "WorkflowStarted", "{}", CREATED)))
                .hasMessageContaining("broker is down");
    }

    @Test
    void closingTheSinkClosesTheProducer() {
        sink.close();

        assertThat(producer.closed).isTrue();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /** Records what is sent; can be told to fail the next send. Only send and close are used by the sink. */
    private static final class FakeProducer {
        final List<ProducerRecord<String, String>> sent = new ArrayList<>();
        RuntimeException failNext;
        boolean closed;

        @SuppressWarnings("unchecked")
        final Producer<String, String> proxy = (Producer<String, String>) Proxy.newProxyInstance(
                Producer.class.getClassLoader(), new Class<?>[] {Producer.class}, (self, method, args) -> {
                    switch (method.getName()) {
                        case "send" -> {
                            if (failNext != null) {
                                return CompletableFuture.failedFuture(failNext);
                            }
                            sent.add((ProducerRecord<String, String>) args[0]);
                            return CompletableFuture.completedFuture(null);
                        }
                        case "close" -> {
                            closed = true;
                            return null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
