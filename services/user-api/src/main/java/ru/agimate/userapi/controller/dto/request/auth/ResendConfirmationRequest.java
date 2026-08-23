package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Confirmation Letter Resend Request DTO")
public record ResendConfirmationRequest(
        @Schema(description = "Address the registration was asked for", example = "user@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email
        String email
) {}
