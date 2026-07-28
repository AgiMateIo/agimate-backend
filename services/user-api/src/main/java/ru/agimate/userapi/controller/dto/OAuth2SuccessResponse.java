package ru.agimate.userapi.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "OAuth2 Success Response DTO")
public record OAuth2SuccessResponse(
        @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        @JsonProperty("access_token")
        String accessToken,

        @Schema(description = "Refresh token identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        @JsonProperty("refresh_token_id")
        String refreshTokenId,

        @Schema(description = "Token type", example = "Bearer")
        @JsonProperty("token_type")
        String tokenType,

        @Schema(description = "Token expiration time in seconds", example = "3600")
        @JsonProperty("expires_in")
        Long expiresIn,

        @Schema(description = "User public ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @JsonProperty("user_id")
        String userId,

        @Schema(description = "User email", example = "user@example.com")
        @JsonProperty("email")
        String email,

        @Schema(description = "User first name", example = "John")
        @JsonProperty("first_name")
        String firstName,

        @Schema(description = "User last name", example = "Doe")
        @JsonProperty("last_name")
        String lastName,

        @Schema(description = "User display name", example = "johndoe")
        @JsonProperty("display_name")
        String displayName
) {}