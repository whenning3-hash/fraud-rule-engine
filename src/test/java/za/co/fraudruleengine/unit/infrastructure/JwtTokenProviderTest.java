package za.co.fraudruleengine.unit.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.co.fraudruleengine.infrastructure.config.JwtTokenProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JWT token generation, parsing, and validation.
 *
 * The signing secret is a valid Base64-encoded 256-bit key (required by HMAC-SHA256).
 */
class JwtTokenProviderTest {

    // Valid Base64-encoded 256-bit key for test use only
    private static final String TEST_SECRET =
            "dGhpcy1pcy1hLXZlcnktc2VjdXJlLXNlY3JldC1rZXktZm9yLWZyYXVkLXJ1bGUtZW5naW5lLWFwcA==";
    private static final long ONE_DAY_MS = 86_400_000L;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", ONE_DAY_MS);
    }

    // ── generateToken / getSubject round-trip ────────────────────────────────

    @Test
    void generateToken_returnsNonNullJwt() {
        String token = provider.generateToken("admin");
        assertThat(token).isNotBlank();
        // JWT structure: three Base64url segments separated by dots
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void getSubject_returnsOriginalSubject() {
        String token = provider.generateToken("analyst");
        assertThat(provider.getSubject(token)).isEqualTo("analyst");
    }

    @Test
    void generateToken_differentSubjects_produceDifferentTokens() {
        String t1 = provider.generateToken("alice");
        String t2 = provider.generateToken("bob");
        assertThat(t1).isNotEqualTo(t2);
    }

    // ── validateToken ────────────────────────────────────────────────────────

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = provider.generateToken("user1");
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_tamperedSignature_returnsFalse() {
        String token = provider.generateToken("user2");
        // Corrupt the last character of the signature segment
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_malformedGarbageString_returnsFalse() {
        assertThat(provider.validateToken("not.a.jwt")).isFalse();
    }

    @Test
    void validateToken_emptyString_returnsFalse() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void validateToken_expiredToken_returnsFalse() {
        // Issue a token that expired 1 second ago
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", -1000L);
        String expiredToken = provider.generateToken("expired-user");
        // Restore normal expiry for validation
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", ONE_DAY_MS);
        assertThat(provider.validateToken(expiredToken)).isFalse();
    }

    @Test
    void validateToken_tokenSignedWithDifferentSecret_returnsFalse() {
        // Generate with current secret, then swap to a different secret for validation
        String token = provider.generateToken("user3");
        String differentSecret =
                "ZGlmZmVyZW50LXNlY3JldC1rZXktZm9yLXRlc3RpbmctcHVycG9zZXMtb25seQ==";
        ReflectionTestUtils.setField(provider, "jwtSecret", differentSecret);
        assertThat(provider.validateToken(token)).isFalse();
    }
}
