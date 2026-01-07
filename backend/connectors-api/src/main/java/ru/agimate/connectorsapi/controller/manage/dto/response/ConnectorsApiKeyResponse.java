package ru.agimate.connectorsapi.controller.manage.dto.response;

import ru.agimate.connectorsapi.database.entities.ConnectorsApiKey;

import java.time.LocalDateTime;
import java.util.UUID;

public record ConnectorsApiKeyResponse(
        UUID pubId,
        String name,
        String description,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ConnectorsApiKeyResponse from(ConnectorsApiKey key) {
        return new ConnectorsApiKeyResponse(
                key.getPubId(),
                key.getName(),
                key.getDescription(),
                key.getEnabled(),
                key.getCreatedAt(),
                key.getUpdatedAt()
        );
    }
}
