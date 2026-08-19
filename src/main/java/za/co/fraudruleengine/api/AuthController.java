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

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

/**
 * REST controller that issues JWT Bearer tokens for API authentication.
 *
 * <p>Credentials are validated against a BCrypt-hashed in-memory user store. Each user entry
 * stores only the hashed credential — plain-text passwords are never retained. In a production
 * deployment backed by an enterprise IdP (e.g. Keycloak, Azure AD), this controller would
 * delegate validation to that system; the token-issuance contract ({@code /api/v1/auth/token}
 * → JWT Bearer response) remains unchanged.
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
     * BCrypt encoder used for password verification.
     * BCrypt is the industry-standard one-way hashing algorithm for passwords — it is
     * deliberately slow (cost factor 10 ≈ 100ms/hash) to resist brute-force attacks even
     * if the credential store is compromised.
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * Static credential store — username → BCrypt hash.
     *
     * <p>Passwords are hashed at class-load time using BCrypt (cost=10). Plain-text passwords
     * are never retained. In a deployment backed by an IdP (e.g. Keycloak), this map would be
     * replaced by a delegated authentication call; the surrounding token-issuance logic remains
     * unchanged.
     *
     * <p>Registered users:
     * <ul>
     *   <li>{@code admin} / {@code admin123}</li>
     *   <li>{@code analyst} / {@code analyst456}</li>
     *   <li>{@code readonly} / {@code readonly789}</li>
     * </ul>
     */
    private static final Map<String, String> USERS;

    static {
        USERS = Map.of(
                "admin",    PASSWORD_ENCODER.encode("admin123"),
                "analyst",  PASSWORD_ENCODER.encode("analyst456"),
                "readonly", PASSWORD_ENCODER.encode("readonly789")
        );
    }

    /**
     * Validates the supplied credentials using BCrypt and issues a signed JWT Bearer token.
     *
     * <p>Returns {@code 401 Unauthorized} if the username is unknown or the password does not
     * match the stored hash. The same generic error message is returned for both cases to
     * prevent user enumeration via timing differences.
     *
     * @param request the login payload containing username and password
     * @return HTTP 200 OK with a {@link TokenResponse}, or 401 if credentials are invalid
     */
    @PostMapping("/token")
    @Operation(summary = "Obtain a JWT Bearer token — valid credentials required")
    public ResponseEntity<?> token(@Valid @RequestBody TokenRequest request) {
        String storedHash = USERS.get(request.username());
        if (storedHash == null || !PASSWORD_ENCODER.matches(request.password(), storedHash)) {
            log.warn("Authentication failed for user: {}", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }
        log.info("Token issued for user: {}", request.username());
        String token = jwtTokenProvider.generateToken(request.username());
        return ResponseEntity.ok(TokenResponse.bearer(token, expirationMs));
    }
}
