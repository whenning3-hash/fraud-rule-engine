package za.co.fraudruleengine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc / Swagger configuration for the Fraud Rule Engine API.
 *
 * <p>Registers a global {@code Bearer Authentication} security scheme so that the Swagger UI
 * presents an "Authorize" button that pre-populates the {@code Authorization: Bearer <token>}
 * header on all requests. This eliminates the need for testers to manually add the header to
 * every endpoint they try.
 *
 * <p>The security scheme name is defined once in {@link #SECURITY_SCHEME_NAME} and referenced
 * from both {@code addSecurityItem} (applies the scheme globally) and
 * {@code addSecuritySchemes} (defines the scheme — name must match exactly). Using a constant
 * ensures the two call sites can never drift out of sync.
 *
 * <p>The generated API documentation is available at:
 * <ul>
 *   <li>Swagger UI: {@code /swagger-ui/index.html}</li>
 *   <li>OpenAPI JSON: {@code /api-docs}</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    /**
     * Security scheme name referenced in both {@link SecurityRequirement} and
     * {@link Components#addSecuritySchemes} — extracted to a constant to ensure the two
     * call sites remain in sync and eliminate the duplicated string literal.
     */
    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

    @Bean
    public OpenAPI fraudRuleEngineOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fraud Rule Engine API")
                        .description("Evaluates configurable fraud rules against transaction events and exposes alerts via REST API")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
