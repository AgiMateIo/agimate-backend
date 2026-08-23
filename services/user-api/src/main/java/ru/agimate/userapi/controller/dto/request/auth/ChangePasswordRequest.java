package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Password Change Request DTO")
public record ChangePasswordRequest(
        @Schema(description = "The password in use now", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String currentPassword,

        @Schema(description = "New password, at least 8 characters",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8, max = 72)
        String newPassword
) {}
