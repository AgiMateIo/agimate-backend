package ru.agimate.userapi.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User Response DTO
 */
@Schema(description = "User Response DTO")
public record UserResponse(
        @Schema(description = "User public ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID pubId,

        @Schema(description = "User email", example = "user@example.com")
        String email,

        @Schema(description = "User first name", example = "John")
        String firstName,

        @Schema(description = "User last name", example = "Doe")
        String lastName,

        @Schema(description = "User display name", example = "johndoe")
        String displayName,

        @Schema(description = "User creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "User update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime updatedAt
) {
}