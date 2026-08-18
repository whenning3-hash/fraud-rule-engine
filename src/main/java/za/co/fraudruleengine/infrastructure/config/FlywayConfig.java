package za.co.fraudruleengine.infrastructure.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Flyway configuration.
 *
 * <p>Spring Boot 4.1.0 moved Flyway auto-configuration out of
 * {@code spring-boot-autoconfigure} into a separate {@code spring-boot-flyway} module.
 * Until that module is resolved from the local Maven cache, this class manually
 * creates a {@link Flyway} bean and runs all pending migrations during startup.
 *
 * <p>Migration scripts live in {@code src/main/resources/db/migration}:
 * <ul>
 *   <li>V1 – transactions table</li>
 *   <li>V2 – fraud_alerts table</li>
 *   <li>V3 – rule_configs table</li>
 *   <li>V4 – seed rule configs (VELOCITY, AMOUNT_THRESHOLD, OFF_HOURS, DUPLICATE)</li>
 * </ul>
 */
@Configuration
public class FlywayConfig {

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
