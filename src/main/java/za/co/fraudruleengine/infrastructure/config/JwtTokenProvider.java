package za.co.fraudruleengine.infrastructure.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * Component responsible for creating, parsing, and validating HMAC-SHA signed JWT tokens.
 *
 * <p>This class encapsulates all JJWT 0.12.x API calls, keeping the JWT library dependency
 * isolated from the rest of the security infrastructure. The signing key is derived from a
 * Base64-encoded secret configured via {@code fraud.security.jwt.secret}; the key must be at
 * least 256 bits (32 bytes) long to satisfy the HMAC-SHA256 minimum key length requirement.
 *
 * <p>Tokens contain a single {@code sub} (subject) claim set to the authenticated username and
 * standard {@code iat} (issued-at) and {@code exp} (expiry) claims. No roles or authorities are
 * embedded in the token; this application uses a simplified single-role model where any valid
 * token grants full API access.
 *
 * <p><strong>Configuration properties:</strong>
 * <ul>
 *   <li>{@code fraud.security.jwt.secret} — required; Base64-encoded HMAC signing key</li>
 *   <li>{@code fraud.security.jwt.expiration-ms} — token lifetime in milliseconds
 *       (default: 86400000 = 24 hours)</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${fraud.security.jwt.secret}")
    private String jwtSecret;

    @Value("${fraud.security.jwt.expiration-ms}")
    private long jwtExpirationMs;

    /**
     * Derives the HMAC-SHA signing key from the Base64-encoded secret property.
     *
     * <p>The key is constructed on each call rather than cached as a field to avoid holding a
     * strong reference to key material longer than necessary, and to allow the secret to be
     * rotated via config refresh without restarting the application.
     *
     * @return the derived {@link SecretKey} suitable for HMAC-SHA256 signing and verification
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a signed JWT token for the given subject.
     *
     * <p>The token is signed with HMAC-SHA (algorithm selected automatically by JJWT based on
     * key length) and contains the standard {@code sub}, {@code iat}, and {@code exp} claims.
     *
     * @param subject the identifier to embed as the JWT {@code sub} claim, typically a username
     * @return a compact, URL-safe signed JWT string in the form {@code header.payload.signature}
     */
    public String generateToken(String subject) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);
        return Jwts.builder()
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Parses a JWT token and extracts the {@code sub} (subject) claim.
     *
     * <p>This method validates the signature before extracting the claim; if the token is
     * invalid or expired a {@link JwtException} will propagate. Callers should invoke
     * {@link #validateToken(String)} before calling this method to avoid unhandled exceptions
     * in the normal request flow.
     *
     * @param token the compact JWT string to parse
     * @return the {@code sub} claim value; never {@code null} for a well-formed token
     * @throws JwtException if the token is malformed, has an invalid signature, or has expired
     */
    public String getSubject(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Validates a JWT token by verifying its signature and checking it has not expired.
     *
     * <p>All JJWT exceptions ({@link JwtException} and its subtypes, plus
     * {@link IllegalArgumentException} for blank/null input) are caught and logged at WARN
     * level — invalid tokens are rejected silently from the caller's perspective, causing the
     * request to proceed unauthenticated and be rejected by the security filter chain.
     *
     * @param token the compact JWT string to validate; may be {@code null} or empty
     * @return {@code true} if the token has a valid signature and has not expired;
     *         {@code false} for any other condition
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }
}
