package com.pool.adapter.spring;

import com.pool.config.ConfigLoader;
import com.pool.config.PoolConfig;
import com.pool.adapter.executor.PoolExecutor;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for Pool.
 */
@Configuration
@ConditionalOnProperty(prefix = "pool", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PoolProperties.class)
public class PoolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PoolAutoConfiguration.class);

    private PoolExecutor poolExecutor;

    @Bean
    @ConditionalOnMissingBean
    public PoolConfig poolConfig(PoolProperties properties) {
        log.info("Loading Pool configuration from: {}", properties.getConfigPath());
        return ConfigLoader.load(properties.getConfigPath());
    }

    @Bean
    @ConditionalOnMissingBean
    public PoolExecutor poolExecutor(PoolConfig config) {
        log.info("Creating PoolExecutor: {}", config.name());
        this.poolExecutor = new TpsPoolExecutor(config);
        return this.poolExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (poolExecutor != null && !poolExecutor.isShutdown()) {
            log.info("Shutting down PoolExecutor");
            poolExecutor.shutdown();
        }
    }
}
