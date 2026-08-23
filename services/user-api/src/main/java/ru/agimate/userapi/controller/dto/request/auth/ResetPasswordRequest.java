package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password the length bounds are repeated in the service, in bytes: this one counts
 *                 characters, and bcrypt counts the 72 bytes it will actually read
 */
@Schema(description = "Password Reset Request DTO")
public record ResetPasswordRequest(
        @Schema(description = "Token from the letter", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String token,

        @Schema(description = "New password, at least 8 characters",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8, max = 72)
        String password
) {}
