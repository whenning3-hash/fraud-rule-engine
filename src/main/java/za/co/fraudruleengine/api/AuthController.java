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

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain a JWT token for API access")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${fraud.security.jwt.expiration-ms:86400000}")
    private long expirationMs;

    @PostMapping("/token")
    @Operation(summary = "Obtain a JWT Bearer token (demo: any username/password accepted)")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        log.info("Token issued for user: {}", request.username());
        String token = jwtTokenProvider.generateToken(request.username());
        return ResponseEntity.ok(TokenResponse.bearer(token, expirationMs));
    }
}
