package za.co.fraudruleengine.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplementary Jackson 2 compatibility configuration.
 *
 * <p>Spring Boot 4 auto-configures a Jackson 3 ({@code tools.jackson.databind.ObjectMapper})
 * instance via {@code JacksonAutoConfiguration} in the {@code spring-boot-jackson} module — that
 * mapper is used by Spring MVC for all HTTP message conversion, as Jackson 3 is the primary JSON
 * library in the Spring 7 / Spring Boot 4 ecosystem.
 *
 * <p>This class contributes a Jackson 2 ({@code com.fasterxml.jackson}) compatibility bean
 * required by third-party libraries that have not yet migrated to the new
 * {@code tools.jackson} API:
 * <ul>
 *   <li><strong>springdoc-openapi 3.x</strong> — still uses {@code com.fasterxml.jackson.databind.ObjectMapper}
 *       for OpenAPI schema generation</li>
 *   <li><strong>JJWT 0.12.x</strong> — uses {@code com.fasterxml.jackson.databind.ObjectMapper}
 *       for claims serialisation</li>
 * </ul>
 *
 * <p>The {@link JavaTimeModule} is registered and {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}
 * is disabled so that {@link java.time.LocalDateTime} and related types are serialised as ISO 8601
 * strings (e.g. {@code "2024-01-15T14:30:00"}) rather than numeric epoch arrays.
 */
@Configuration
public class JacksonConfig {

    /**
     * Jackson 2 compatibility {@code ObjectMapper} with Java time support.
     *
     * <p>Spring Boot 4's primary Jackson 3 mapper is provided by {@code JacksonAutoConfiguration}.
     * This bean is scoped specifically to support libraries that depend on the {@code com.fasterxml}
     * namespace and is injected directly into {@link FraudEvaluationService} for rule result
     * serialisation.
     *
     * @return a configured Jackson 2 {@link com.fasterxml.jackson.databind.ObjectMapper}
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Serialise dates as ISO 8601 strings, not numeric timestamp arrays
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
