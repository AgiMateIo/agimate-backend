package ru.agimate.userapi.controller.dto.response;

import ru.agimate.userapi.database.entities.ServiceApiKey;

import java.time.LocalDateTime;
import java.util.UUID;

public record ServiceApiKeyResponse(
        UUID pubId,
        String name,
        String description,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ServiceApiKeyResponse from(ServiceApiKey key) {
        return new ServiceApiKeyResponse(
                key.getPubId(),
                key.getName(),
                key.getDescription(),
                key.getEnabled(),
                key.getCreatedAt(),
                key.getUpdatedAt()
        );
    }
}
