package com.pool.adapter.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for Pool.
 */
@ConfigurationProperties(prefix = "pool")
public class PoolProperties {

    /**
     * Whether Pool is enabled.
     */
    private boolean enabled = true;

    /**
     * Path to the Pool configuration file.
     * Supports classpath: prefix for classpath resources.
     */
    private String configPath = "classpath:pool-expr.yaml";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }
}
