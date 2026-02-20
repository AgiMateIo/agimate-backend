package ru.agimate.userapi.controller.dto.response;

import ru.agimate.userapi.database.entities.ServiceApiKey;
import ru.agimate.userapi.service.ServiceApiKeyService;

import java.time.LocalDateTime;
import java.util.UUID;

public record ServiceApiKeyResponse(
        UUID pubId,
        String name,
        String description,
        String maskedKeyId,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ServiceApiKeyResponse from(ServiceApiKey key) {
        String maskedKeyId = ServiceApiKeyService.API_KEY_PREFIX + key.getKeyId().substring(0, 4) + "****";
        return new ServiceApiKeyResponse(
                key.getPubId(),
                key.getName(),
                key.getDescription(),
                maskedKeyId,
                key.getEnabled(),
                key.getCreatedAt(),
                key.getUpdatedAt()
        );
    }
}
