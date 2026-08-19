package za.co.fraudruleengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Fraud Rule Engine Spring Boot application.
 *
 * <p>Bootstraps the entire Spring context — component scanning, auto-configuration,
 * Flyway migrations, HikariCP connection pool, and embedded Tomcat — in a single
 * {@link SpringApplication#run} call.
 *
 * <p><b>Virtual threads</b>: with {@code spring.threads.virtual.enabled=true} set in
 * {@code application.yml}, all Tomcat request-handling threads, {@code @Async} tasks,
 * and {@code @Scheduled} jobs are dispatched on Java 25 Project Loom virtual threads.
 * No additional configuration is required here — Spring Boot wires the virtual-thread
 * executor automatically when the property is present.
 */
@SpringBootApplication
public class FraudRuleEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudRuleEngineApplication.class, args);
    }
}
