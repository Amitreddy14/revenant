package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.persistence.TaskNotifier;
import io.github.amitreddy14.revenant.persistence.WorkflowStore;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Spring-free persistence module into the application. */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

    @Bean
    WorkflowStore workflowStore(DataSource dataSource, TaskNotifier notifier) {
        return new WorkflowStore(dataSource, notifier);
    }
}
