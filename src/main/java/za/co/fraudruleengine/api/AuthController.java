package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.fraudruleengine.api.dto.TokenRequest;
import za.co.fraudruleengine.api.dto.TokenResponse;
import za.co.fraudruleengine.infrastructure.config.JwtTokenProvider;

/**
 * REST controller that issues JWT Bearer tokens for API authentication.
 *
 * <p>This controller exists to make the demo self-contained: any username/password combination
 * is accepted and a signed JWT is returned. In a production deployment, this endpoint would be
 * replaced by an integration with a proper identity provider (e.g. Keycloak, Azure AD) and
 * credential validation would be delegated to that system.
 *
 * <p>The {@code /api/v1/auth/**} path pattern is explicitly permit-listed in
 * {@link za.co.fraudruleengine.infrastructure.config.SecurityConfig} so that the token endpoint
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
     * Issues a signed JWT Bearer token for the supplied username.
     *
     * <p><strong>Demo behaviour:</strong> no password validation is performed; the username is
     * used as the JWT {@code sub} claim directly. Downstream endpoints extract this subject via
     * {@link JwtTokenProvider#getSubject} for logging purposes.
     *
     * @param request the login payload containing the desired username (and ignored password)
     * @return HTTP 200 OK with a {@link TokenResponse} containing the signed JWT and its
     *         expiry duration in milliseconds
     */
    @PostMapping("/token")
    @Operation(summary = "Obtain a JWT Bearer token (demo: any username/password accepted)")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        log.info("Token issued for user: {}", request.username());
        String token = jwtTokenProvider.generateToken(request.username());
        return ResponseEntity.ok(TokenResponse.bearer(token, expirationMs));
    }
}
