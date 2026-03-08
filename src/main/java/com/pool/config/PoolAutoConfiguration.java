package com.pool.config;

import com.pool.adapter.executor.PoolExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for Pool.
 * Components are auto-discovered via @Component annotations.
 * This class only enables configuration properties and manages lifecycle.
 */
@Configuration
@ConditionalOnProperty(prefix = "pool", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PoolConfig.class)
public class PoolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PoolAutoConfiguration.class);

    @Autowired(required = false)
    private PoolExecutor poolExecutor;

    @PreDestroy
    public void shutdown() {
        if (poolExecutor != null && !poolExecutor.isShutdown()) {
            log.info("Shutting down PoolExecutor");
            poolExecutor.shutdown();
        }
    }
}
