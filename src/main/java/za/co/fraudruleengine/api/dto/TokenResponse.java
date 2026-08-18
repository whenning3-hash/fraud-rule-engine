package za.co.fraudruleengine.api.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    public static TokenResponse bearer(String token, long expiresInMs) {
        return new TokenResponse(token, "Bearer", expiresInMs / 1000);
    }
}
