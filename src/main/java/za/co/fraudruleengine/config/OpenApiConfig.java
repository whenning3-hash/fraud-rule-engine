package za.co.fraudruleengine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
