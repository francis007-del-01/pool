package com.pool;

import com.pool.core.PoolExecutor;
import com.pool.core.TaskContext;
import com.pool.core.TaskContextFactory;
import com.pool.spring.EnablePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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

            // Context (simulating headers/metadata)
            Map<String, String> context = Map.of("clientId", "demo-app");

            // Submit some tasks with different priorities
            
            // High priority: NORTH_AMERICA + PLATINUM + HIGH_VALUE
            String json1 = """
                {
                    "region": "NORTH_AMERICA",
                    "customerTier": "PLATINUM",
                    "transactionAmount": 500000.0,
                    "priority": 95
                }
                """;
            TaskContext ctx1 = TaskContextFactory.create(json1, context);
            Future<String> task1 = poolExecutor.submit(ctx1, () -> {
                Thread.sleep(100);
                return "Task 1 completed (NA/PLATINUM/HIGH_VALUE)";
            });

            // Medium priority: NORTH_AMERICA + GOLD
            String json2 = """
                {
                    "region": "NORTH_AMERICA",
                    "customerTier": "GOLD",
                    "transactionAmount": 5000.0
                }
                """;
            TaskContext ctx2 = TaskContextFactory.create(json2, context);
            Future<String> task2 = poolExecutor.submit(ctx2, () -> {
                Thread.sleep(100);
                return "Task 2 completed (NA/GOLD)";
            });

            // Lower priority: EUROPE + DEFAULT
            String json3 = """
                {
                    "region": "EUROPE",
                    "customerTier": "SILVER",
                    "priority": 80
                }
                """;
            TaskContext ctx3 = TaskContextFactory.create(json3, context);
            Future<String> task3 = poolExecutor.submit(ctx3, () -> {
                Thread.sleep(100);
                return "Task 3 completed (EUROPE)";
            });

            // Lowest priority: DEFAULT region
            String json4 = """
                {
                    "region": "ASIA_PACIFIC",
                    "customerTier": "BRONZE"
                }
                """;
            TaskContext ctx4 = TaskContextFactory.create(json4, context);
            Future<String> task4 = poolExecutor.submit(ctx4, () -> {
                Thread.sleep(100);
                return "Task 4 completed (ASIA-PACIFIC)";
            });

            // Wait for all tasks to complete
            log.info("Result: {}", task1.get(5, TimeUnit.SECONDS));
            log.info("Result: {}", task2.get(5, TimeUnit.SECONDS));
            log.info("Result: {}", task3.get(5, TimeUnit.SECONDS));
            log.info("Result: {}", task4.get(5, TimeUnit.SECONDS));

            log.info("=== Pool Demo Completed ===");
            log.info("Queue size: {}, Active threads: {}", 
                    poolExecutor.getQueueSize(), poolExecutor.getActiveCount());

            // Shutdown the executor and exit (for demo purposes)
            poolExecutor.shutdown();
            poolExecutor.awaitTermination(10, TimeUnit.SECONDS);
            System.exit(0);
        };
    }
}
