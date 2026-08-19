package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the {@code POST /api/v1/auth/token} endpoint.
 *
 * <p>Carries the caller's credentials for username/password authentication.
 * On successful validation a short-lived JWT Bearer token is returned via
 * {@link TokenResponse} which must be included in the {@code Authorization} header of
 * all subsequent API calls.
 *
 * <p>Both fields are validated as {@code @NotBlank} — a missing or whitespace-only
 * value returns {@code 400 Bad Request} before the credential check runs, which prevents
 * unnecessary database lookups for obviously invalid requests.
 *
 * <p><b>Security note</b>: this endpoint is covered by a strict per-IP rate limit
 * (default 10 req/min) via {@link za.co.fraudruleengine.filter.RateLimitFilter} to
 * prevent automated credential-stuffing and password-spray attacks.
 */
public record TokenRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password
) {}
