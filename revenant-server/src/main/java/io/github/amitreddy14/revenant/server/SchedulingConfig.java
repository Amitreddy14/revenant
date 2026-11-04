package io.github.amitreddy14.revenant.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} methods. Each job can still be switched off with its own property. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {}
