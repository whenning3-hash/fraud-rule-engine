package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.fraudruleengine.api.dto.TokenRequest;
import za.co.fraudruleengine.api.dto.TokenResponse;
import za.co.fraudruleengine.config.JwtTokenProvider;

import java.util.Map;

/**
 * REST controller that issues JWT Bearer tokens for API authentication.
 *
 * <p>Credentials are validated against a static in-memory user map. This keeps the service
 * self-contained for demo and testing purposes. In a production deployment this would be
 * replaced by an integration with a proper identity provider (e.g. Keycloak, Azure AD) and
 * credential validation would be delegated to that system.
 *
 * <p>The {@code /api/v1/auth/**} path pattern is explicitly permit-listed in
 * {@link za.co.fraudruleengine.config.SecurityConfig} so that the token endpoint
 * is accessible without a pre-existing token.
 *
 * <p>The issued token is signed with the HMAC-SHA key configured via
 * {@code fraud.security.jwt.secret} and expires after {@code fraud.security.jwt.expiration-ms}
 * milliseconds (default: 24 hours).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain a JWT token for API access")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    /** Token lifetime in milliseconds; configurable via {@code fraud.security.jwt.expiration-ms}. */
    @Value("${fraud.security.jwt.expiration-ms:86400000}")
    private long expirationMs;

    /**
     * Static credential store — username → password.
     *
     * <p>In a real deployment this would be backed by a user store or OAuth2 provider.
     * Passwords here are plain-text for demo clarity; a production system would store
     * BCrypt hashes and use {@code PasswordEncoder.matches()}.
     */
    private static final Map<String, String> USERS = Map.of(
            "admin",    "admin123",
            "analyst",  "analyst456",
            "readonly", "readonly789"
    );

    /**
     * Validates the supplied credentials and issues a signed JWT Bearer token.
     *
     * <p>Returns {@code 401 Unauthorized} if the username is unknown or the password does not
     * match. On success, returns the signed JWT and its expiry duration.
     *
     * @param request the login payload containing username and password
     * @return HTTP 200 OK with a {@link TokenResponse}, or 401 if credentials are invalid
     */
    @PostMapping("/token")
    @Operation(summary = "Obtain a JWT Bearer token — valid credentials required")
    public ResponseEntity<?> token(@Valid @RequestBody TokenRequest request) {
        String expected = USERS.get(request.username());
        if (expected == null || !expected.equals(request.password())) {
            log.warn("Authentication failed for user: {}", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }
        log.info("Token issued for user: {}", request.username());
        String token = jwtTokenProvider.generateToken(request.username());
        return ResponseEntity.ok(TokenResponse.bearer(token, expirationMs));
    }
}
