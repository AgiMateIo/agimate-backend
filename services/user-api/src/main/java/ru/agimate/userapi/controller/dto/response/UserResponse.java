package ru.agimate.userapi.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.common.security.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "User Response DTO")
public record UserResponse(
        @Schema(description = "User ID", example = "0190a3b7-1234-7abc-8def-0123456789ab")
        UUID id,

        @Schema(description = "User email", example = "user@example.com")
        String email,

        @Schema(description = "User first name", example = "John")
        String firstName,

        @Schema(description = "User last name", example = "Doe")
        String lastName,

        @Schema(description = "User display name", example = "johndoe")
        String displayName,

        @Schema(description = "User role — drives access to admin-only features", example = "USER")
        UserRole role,

        @Schema(description = "Who invited this user; absent for everyone who came on their own")
        UUID referredBy,

        @Schema(description = "User creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "User update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime updatedAt
) {
}