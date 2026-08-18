package za.co.fraudruleengine.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration defining the stateless JWT-based security filter chain.
 *
 * <p>This configuration enforces the following security model:
 * <ul>
 *   <li><strong>Stateless sessions</strong> — {@link SessionCreationPolicy#STATELESS} ensures
 *       no HTTP session is created or used. Authentication state is carried entirely within the
 *       JWT token on every request.</li>
 *   <li><strong>CSRF disabled</strong> — CSRF protection is not required for stateless REST APIs
 *       because there is no session cookie for an attacker to exploit.</li>
 *   <li><strong>JWT pre-authentication</strong> — {@link JwtAuthenticationFilter} runs before
 *       Spring's default {@link UsernamePasswordAuthenticationFilter}, intercepting and validating
 *       the Bearer token before the standard authentication path executes.</li>
 * </ul>
 *
 * <p>The following paths are publicly accessible when security is enabled:
 * <ul>
 *   <li>{@code /api/v1/auth/**} — token issuance endpoint (callers must be able to obtain a token)</li>
 *   <li>{@code /actuator/health} — liveness probe for container orchestration</li>
 * </ul>
 *
 * <p>Swagger UI ({@code /swagger-ui/**}, {@code /api-docs/**}) is disabled in production via
 * {@code springdoc.api-docs.enabled=false} in {@code application.yml} and therefore never
 * reached. It is re-enabled only by the {@code local} profile where security is off entirely.
 *
 * <p>Security can be disabled entirely for local development via
 * {@code fraud.security.enabled=false}, which permits all requests without a token. This must
 * never be set to {@code false} in any non-local environment.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Feature flag to disable security for local development.
     * Defaults to {@code true} (security enabled). Must remain {@code true} in all
     * non-local environments.
     */
    @Value("${fraud.security.enabled:true}")
    private boolean securityEnabled;

    /**
     * Configures and builds the primary Spring Security filter chain.
     *
     * <p>When {@code fraud.security.enabled} is {@code true} (the default), the chain:
     * <ol>
     *   <li>Disables CSRF (not needed for stateless APIs)</li>
     *   <li>Sets session management to {@code STATELESS}</li>
     *   <li>Permit-lists the auth, documentation, and health endpoints</li>
     *   <li>Requires authentication for all other requests</li>
     *   <li>Inserts the JWT filter before the standard username/password filter</li>
     * </ol>
     *
     * <p>When {@code fraud.security.enabled} is {@code false}, all requests are permitted
     * without a token. The JWT filter is not added in this case.
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if any Spring Security configuration step fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (securityEnabled) {
            http.authorizeHttpRequests(auth -> auth
                    // Public endpoints — no token required
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    // Swagger is disabled in production (springdoc.api-docs.enabled=false).
                    // Belt-and-suspenders: if somehow reached, require authentication.
                    .requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").authenticated()
                    // All other endpoints require a valid JWT
                    .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            // Development mode: bypass all authentication — NEVER use in production
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }
}
