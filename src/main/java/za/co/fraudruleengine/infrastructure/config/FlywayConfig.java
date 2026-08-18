package za.co.fraudruleengine.infrastructure.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Flyway database migration configuration.
 *
 * <p>Spring Boot 4.1.0 moved Flyway auto-configuration out of
 * {@code spring-boot-autoconfigure} into a separate {@code spring-boot-flyway} module.
 * Until that module is resolved from the local Maven cache, this class manually creates a
 * {@link Flyway} bean and runs all pending migrations eagerly during application startup.
 *
 * <p>Migration scripts live in {@code src/main/resources/db/migration}:
 * <ul>
 *   <li>V1 – creates the {@code transactions} table</li>
 *   <li>V2 – creates the {@code fraud_alerts} and {@code fraud_alert_matched_rules} tables</li>
 *   <li>V3 – creates the {@code rule_configs} table</li>
 *   <li>V4 – seeds the four default rule configs (VELOCITY, AMOUNT_THRESHOLD, OFF_HOURS, DUPLICATE)</li>
 * </ul>
 *
 * <p>{@code baselineOnMigrate(true)} is set to allow Flyway to run against a database that
 * already has schema objects (e.g. from a previous manual setup), treating the existing state
 * as baseline version 1 rather than failing.
 */
@Configuration
public class FlywayConfig {

    /**
     * Creates and executes Flyway migrations against the application's primary data source.
     *
     * <p>This bean is eagerly initialised by Spring, meaning migrations run before any JPA
     * entity validation or repository access occurs, ensuring the schema is always up to date
     * before the application starts serving requests.
     *
     * @param dataSource the application's primary JDBC data source, auto-configured by Spring Boot
     * @return the configured and migrated {@link Flyway} instance
     */
    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
