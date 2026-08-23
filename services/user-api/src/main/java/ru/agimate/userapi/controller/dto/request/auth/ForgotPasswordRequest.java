package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Password Reset Letter Request DTO")
public record ForgotPasswordRequest(
        @Schema(description = "Where to send the letter, if an account with this address exists",
                example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email
        String email
) {}
