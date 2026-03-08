package com.pool.config;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for adapters section (executors).
 */
@Data
public class AdaptersConfig {

    /**
     * List of executor specifications.
     */
    @Valid
    private List<ExecutorSpec> executors = new ArrayList<>();
}
