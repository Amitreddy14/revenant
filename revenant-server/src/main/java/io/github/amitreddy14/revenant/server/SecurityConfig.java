package io.github.amitreddy14.revenant.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API key authentication (ADR-0014).
 *
 * <ul>
 *   <li>{@code revenant.security.api-keys} (env {@code REVENANT_API_KEYS}): comma-separated keys, each
 *       at least 16 characters. Several keys let you rotate: add the new one, move clients, drop the old.
 *   <li>{@code revenant.security.required} (env {@code REVENANT_SECURITY_REQUIRED}): when true the server
 *       refuses to start without a key. Set it in every deployment that is reachable from a network.
 * </ul>
 *
 * With no keys and {@code required=false} the API is open, which keeps local development simple; the
 * server logs a warning so that is never a surprise.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    ApiKeys apiKeys(
            @Value("${revenant.security.api-keys:}") String configured,
            @Value("${revenant.security.required:false}") boolean required) {
        ApiKeys keys = ApiKeys.parse(configured);
        if (!keys.enabled() && required) {
            throw new IllegalStateException(
                    "revenant.security.required is true but no revenant.security.api-keys are set");
        }
        if (keys.enabled()) {
            LOG.info("API key authentication is ON ({} key(s))", keys.count());
        } else {
            LOG.warn("API key authentication is OFF: anyone who can reach this port can use the API. "
                    + "Set REVENANT_API_KEYS before exposing the server.");
        }
        return keys;
    }

    /** Spring Boot adds every {@code Filter} bean to the servlet chain; the filter picks its own paths. */
    @Bean
    ApiKeyFilter apiKeyFilter(ApiKeys keys) {
        return new ApiKeyFilter(keys);
    }
}
