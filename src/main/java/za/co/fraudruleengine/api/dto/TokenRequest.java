package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password
) {}
