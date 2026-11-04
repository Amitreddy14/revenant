package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.persistence.TaskNotifier;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spreads "tasks may be ready" hints between server instances through Redis pub/sub (ADR-0012).
 *
 * <p>Redis is only an accelerator (ADR-0004). Nothing here can lose or corrupt work:
 * <ul>
 *   <li>the local {@link WakeUpSignals} are always signalled first, whether or not Redis works;
 *   <li>publishing is fire-and-forget and never blocks or fails the caller;
 *   <li>the connection is made on a background thread and retried, so the server starts and runs
 *       with Redis down, and pollers simply fall back to their timeout.
 * </ul>
 */
final class RedisWakeUps implements TaskNotifier, AutoCloseable {

    static final String CHANNEL_PREFIX = "revenant:wake:";
    private static final Logger LOG = LoggerFactory.getLogger(RedisWakeUps.class);
    private static final long RETRY_MILLIS = 5_000;

    private final WakeUpSignals local;
    private final RedisClient client;
    private final Thread connector;
    private volatile StatefulRedisConnection<String, String> publisher;
    private volatile StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile boolean closed;

    /** @param uri for example {@code redis://localhost:6379}; it is not contacted until {@link #start()} */
    RedisWakeUps(String uri, WakeUpSignals local) {
        this.local = local;
        this.client = RedisClient.create(uri);
        this.client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        this.connector = new Thread(this::connectUntilItWorks, "revenant-redis-connector");
        this.connector.setDaemon(true);
    }

    void start() {
        connector.start();
    }

    @Override
    public void tasksMayBeReady(TaskKind kind) {
        local.signal(kind);
        StatefulRedisConnection<String, String> connection = publisher;
        if (connection == null) {
            return; // Redis not reachable (yet): pollers use their timeout
        }
        try {
            connection.async().publish(CHANNEL_PREFIX + kind.name(), "1");
        } catch (RuntimeException e) {
            LOG.debug("Could not publish a wake-up: {}", e.toString());
        }
    }

    boolean connected() {
        return publisher != null && subscriber != null;
    }

    private void connectUntilItWorks() {
        boolean warned = false;
        while (!closed) {
            StatefulRedisConnection<String, String> pub = null;
            StatefulRedisPubSubConnection<String, String> sub = null;
            try {
                pub = client.connect();
                sub = client.connectPubSub();
                sub.addListener(new RedisPubSubAdapter<String, String>() {
                    @Override
                    public void message(String channel, String message) {
                        if (channel.startsWith(CHANNEL_PREFIX)) {
                            try {
                                local.signal(TaskKind.valueOf(channel.substring(CHANNEL_PREFIX.length())));
                            } catch (IllegalArgumentException ignored) {
                                // not one of our kinds
                            }
                        }
                    }
                });
                String[] channels = new String[TaskKind.values().length];
                for (int i = 0; i < channels.length; i++) {
                    channels[i] = CHANNEL_PREFIX + TaskKind.values()[i].name();
                }
                sub.sync().subscribe(channels);
                subscriber = sub;
                publisher = pub;
                LOG.info("Redis wake-ups connected");
                return;
            } catch (RuntimeException e) {
                closeQuietly(pub);
                closeQuietly(sub);
                if (!warned) {
                    LOG.warn("Redis is not available, workers will poll PostgreSQL only ({}). Retrying in the background.",
                            e.toString());
                    warned = true;
                }
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void closeQuietly(AutoCloseable connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        connector.interrupt();
        closeQuietly(publisher);
        closeQuietly(subscriber);
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
    }
}
