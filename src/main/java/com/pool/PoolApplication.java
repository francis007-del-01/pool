package com.pool;

import com.pool.core.PoolExecutor;
import com.pool.core.TaskContext;
import com.pool.spring.EnablePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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

            // Submit some tasks with different priorities
            
            // High priority: NORTH_AMERICA + PLATINUM + HIGH_VALUE
            Future<String> task1 = poolExecutor.submit(() -> {
                Thread.sleep(100);
                return "Task 1 completed (NA/PLATINUM/HIGH_VALUE)";
            }, TaskContext.builder()
                    .taskId("task-1")
                    .requestVariable("region", "NORTH_AMERICA")
                    .requestVariable("customerTier", "PLATINUM")
                    .requestVariable("transactionAmount", 500000.0)
                    .requestVariable("priority", 95)
                    .build());

            // Medium priority: NORTH_AMERICA + GOLD
            Future<String> task2 = poolExecutor.submit(() -> {
                Thread.sleep(100);
                return "Task 2 completed (NA/GOLD)";
            }, TaskContext.builder()
                    .taskId("task-2")
                    .requestVariable("region", "NORTH_AMERICA")
                    .requestVariable("customerTier", "GOLD")
                    .requestVariable("transactionAmount", 5000.0)
                    .build());

            // Lower priority: EUROPE + DEFAULT
            Future<String> task3 = poolExecutor.submit(() -> {
                Thread.sleep(100);
                return "Task 3 completed (EUROPE)";
            }, TaskContext.builder()
                    .taskId("task-3")
                    .requestVariable("region", "EUROPE")
                    .requestVariable("customerTier", "SILVER")
                    .requestVariable("priority", 80)
                    .build());

            // Lowest priority: DEFAULT region
            Future<String> task4 = poolExecutor.submit(() -> {
                Thread.sleep(100);
                return "Task 4 completed (ASIA-PACIFIC)";
            }, TaskContext.builder()
                    .taskId("task-4")
                    .requestVariable("region", "ASIA_PACIFIC")
                    .requestVariable("customerTier", "BRONZE")
                    .build());

            // Wait for all tasks to complete
            log.info("Result: {}", task1.get(5, TimeUnit.SECONDS));
            log.info("Result: {}", task2.get(5, TimeUnit.SECONDS));
            log.info("Result: {}", task3.get(5, TimeUnit.SECONDS));
            log.info("Result: {}", task4.get(5, TimeUnit.SECONDS));

            log.info("=== Pool Demo Completed ===");
            log.info("Queue size: {}, Active threads: {}", 
                    poolExecutor.getQueueSize(), poolExecutor.getActiveCount());
        };
    }
}
