package ru.agimate.mobileapi.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.mobileapi.database.entities.ConnectionKey;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Connection key information (without the actual key)")
public record ConnectionKeyResponse(
        @Schema(description = "Public ID of the key")
        UUID id,

        @Schema(description = "Key name/label", example = "My Home Device")
        String name,

        @Schema(description = "Key description")
        String description,

        @Schema(description = "Key prefix for identification", example = "agm_xxxx")
        String keyPrefix,

        @Schema(description = "Whether the key is enabled")
        boolean enabled,

        @Schema(description = "Requests per hour limit (null = unlimited)")
        Integer requestsPerHour,

        @Schema(description = "Last time the key was used")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastUsedAt,

        @Schema(description = "Total number of times the key has been used")
        Long usageCount,

        @Schema(description = "Key expiration date (null = never expires)")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expiresAt,

        @Schema(description = "IP whitelist")
        String ipWhitelist,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {
    public static ConnectionKeyResponse from(ConnectionKey key) {
        return new ConnectionKeyResponse(
                key.getPubId(),
                key.getName(),
                key.getDescription(),
                key.getKeyPrefix() + "****",
                key.getEnabled(),
                key.getRequestsPerHour(),
                key.getLastUsedAt(),
                key.getUsageCount(),
                key.getExpiresAt(),
                key.getIpWhitelist(),
                key.getCreatedAt()
        );
    }
}
