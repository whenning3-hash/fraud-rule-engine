package za.co.fraudruleengine.api.dto;

/**
 * API response DTO for a successfully issued JWT Bearer token.
 *
 * <p>Returned as the body of a {@code 200 OK} response from
 * {@code POST /api/v1/auth/token} when the supplied credentials are valid.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code accessToken} — the signed JWT; must be sent in subsequent requests as
 *       {@code Authorization: Bearer <accessToken>}.</li>
 *   <li>{@code tokenType} — always {@code "Bearer"} per RFC 6750.</li>
 *   <li>{@code expiresIn} — token lifetime in <em>seconds</em> (converted from the
 *       millisecond value in {@code fraud.security.jwt.expiration-ms}).  Callers should
 *       refresh before this deadline to avoid authentication errors.</li>
 * </ul>
 *
 * <p>Use the {@link #bearer(String, long)} factory method to construct a response from
 * the raw token string and millisecond expiry supplied by
 * {@link za.co.fraudruleengine.config.JwtTokenProvider}.
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    public static TokenResponse bearer(String token, long expiresInMs) {
        return new TokenResponse(token, "Bearer", expiresInMs / 1000);
    }
}
