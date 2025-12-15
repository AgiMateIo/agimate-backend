package ru.agimate.userapi.controller.dto.request.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request record for refresh token endpoint
 */
public record RefreshTokenRequest(
    @JsonProperty("refreshToken")
    @NotBlank(message = "Refresh token is required")
    String refreshToken
) {}