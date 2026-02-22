package com.pool;

import com.pool.adapter.executor.PoolExecutor;
import com.pool.adapter.executor.tps.TpsPoolExecutor;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.adapter.spring.EnablePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Example Spring Boot application demonstrating Pool usage.
 */
@SpringBootApplication
@EnablePool
public class PoolApplication {

    private static final Logger log = LoggerFactory.getLogger(PoolApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(PoolApplication.class, args);
    }

    @Bean
    public CommandLineRunner demo(PoolExecutor poolExecutor) {
        return args -> {
            log.info("=== Pool Demo Started ===");

            TpsPoolExecutor executor = (TpsPoolExecutor) poolExecutor;
            log.info("Initial stats: {}", executor.getStats());

            // Context (simulating headers/metadata)
            Map<String, String> context = Map.of("clientId", "demo-app");

            // Submit 20 tasks to trigger scale-up (more than core pool size of 10)
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String json = """
                    {
                        "region": "NORTH_AMERICA",
                        "customerTier": "GOLD",
                        "taskNumber": %d
                    }
                    """.formatted(i);
                TaskContext ctx = TaskContextFactory.create(json, context);
                int taskNum = i;
                futures.add(executor.submit(ctx, () -> {
                    Thread.sleep(500);  // Simulate work
                    return "Task " + taskNum + " completed";
                }));
            }

            log.info("After submitting 20 tasks, stats: {}", executor.getStats());

            // Wait for all tasks to complete
            for (int i = 0; i < futures.size(); i++) {
                log.info("Result: {}", futures.get(i).get(10, TimeUnit.SECONDS));
            }

            log.info("=== All Tasks Completed ===");
            log.info("Stats after tasks: {}", executor.getStats());

            // Shutdown the executor and exit (for demo purposes)
            poolExecutor.shutdown();
            poolExecutor.awaitTermination(10, TimeUnit.SECONDS);
            System.exit(0);
        };
    }
}
