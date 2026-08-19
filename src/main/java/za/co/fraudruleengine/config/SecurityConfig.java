package za.co.fraudruleengine.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
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
 * <p>Swagger UI ({@code /swagger-ui/**}, {@code /api-docs/**}) is disabled in the base
 * {@code application.yml} via {@code springdoc.api-docs.enabled=false}. It is re-enabled in
 * {@code application-local.yml} for interactive API access at
 * {@code http://localhost:8080/swagger-ui/index.html}. Swagger routes are gated behind
 * {@code .authenticated()} so a valid Bearer token is required to access them.
 *
 * <p>Security can be disabled entirely via {@code fraud.security.enabled=false}. This flag
 * exists solely for integration tests, which override it using {@code @TestPropertySource}.
 * The {@code local} profile (the only active profile) keeps security fully enabled.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Feature flag controlling JWT enforcement.
     * Defaults to {@code true} (security enforced). The only supported use of {@code false}
     * is in integration tests via {@code @TestPropertySource} — never disable in any running environment.
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
     * without a token. The JWT filter is not added in this case. This mode is used exclusively
     * by integration tests via {@code @TestPropertySource(properties = "fraud.security.enabled=false")}
     * so that test assertions do not require acquiring JWT tokens at runtime.
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
            // RFC 7235: return 401 Unauthorized (not 403 Forbidden) when credentials are absent.
            // Spring Security 6's default falls back to 403 for unauthenticated requests when no
            // AuthenticationEntryPoint is set, which misleads clients that check status codes to
            // decide whether to re-authenticate (401) or show an "access denied" error (403).
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write(
                                "{\"status\":401,\"title\":\"Unauthorized\","
                                + "\"detail\":\"Authentication is required. "
                                + "Provide a valid Bearer token in the Authorization header.\"}");
                    })
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            // Security bypassed — used ONLY by integration tests via @TestPropertySource.
            // Never set fraud.security.enabled=false in any running environment profile.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }
}
