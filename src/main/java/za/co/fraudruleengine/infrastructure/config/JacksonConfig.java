package za.co.fraudruleengine.infrastructure.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplementary Jackson configuration.
 *
 * <p>Spring Boot 4 auto-configures a Jackson 3 ({@code tools.jackson.databind.ObjectMapper})
 * instance via {@code JacksonAutoConfiguration} in {@code spring-boot-jackson} — that mapper
 * is used by Spring MVC for all HTTP message conversion (Jackson 3 is the primary JSON library
 * in Spring Boot 4).
 *
 * <p>This class only contributes a Jackson 2 compatibility bean required by third-party
 * libraries that have not yet migrated to the {@code tools.jackson} API:
 * <ul>
 *   <li>springdoc-openapi 3.x — still uses {@code com.fasterxml.jackson.databind.ObjectMapper}</li>
 *   <li>JJWT 0.12.x — still uses {@code com.fasterxml.jackson.databind.ObjectMapper}</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    /**
     * Jackson 2 compatibility {@code ObjectMapper}.
     * Spring Boot 4's primary Jackson 3 mapper is provided by {@code JacksonAutoConfiguration}.
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
