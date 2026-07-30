package ru.agimate.userapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.common.security.UserRole;

@Schema(description = "Request to change a user's role")
public record UpdateUserRoleRequest(
        @Schema(description = "New role", example = "USER")
        @NotNull UserRole role
) {
}
