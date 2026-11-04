package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.persistence.TaskNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task wake-ups (ADR-0012). The in-memory signals always exist. With Redis enabled (the default)
 * hints also reach other server instances; with it disabled, or unreachable, workers just poll.
 */
@Configuration(proxyBeanMethods = false)
class WakeUpConfig {

    @Bean
    WakeUpSignals wakeUpSignals() {
        return new WakeUpSignals();
    }

    @Bean
    @ConditionalOnProperty(name = "revenant.redis.enabled", havingValue = "true", matchIfMissing = true)
    RedisWakeUps redisWakeUps(@Value("${revenant.redis.url:redis://localhost:6379}") String url,
            WakeUpSignals signals) {
        RedisWakeUps wakeUps = new RedisWakeUps(url, signals);
        wakeUps.start();
        return wakeUps;
    }

    @Bean
    @ConditionalOnProperty(name = "revenant.redis.enabled", havingValue = "false")
    TaskNotifier localOnlyNotifier(WakeUpSignals signals) {
        return signals::signal;
    }
}
