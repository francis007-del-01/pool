package com.pool.exception;

/**
 * Exception thrown when configuration is invalid.
 * Results in fail-fast at startup.
 */
public class ConfigurationException extends PoolException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
