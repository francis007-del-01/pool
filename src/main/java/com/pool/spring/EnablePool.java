package com.pool.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enable Pool executor in a Spring Boot application.
 * 
 * Usage:
 * <pre>
 * &#64;SpringBootApplication
 * &#64;EnablePool
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(PoolAutoConfiguration.class)
public @interface EnablePool {
}
