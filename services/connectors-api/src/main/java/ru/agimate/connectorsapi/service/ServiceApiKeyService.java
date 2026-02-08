package ru.agimate.connectorsapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.apikey.ApiKeyUtils;
import ru.agimate.common.util.apikey.GeneratedApiKey;
import ru.agimate.common.util.apikey.ParsedApiKey;
import ru.agimate.connectorsapi.database.entities.ServiceApiKey;
import ru.agimate.connectorsapi.database.repositories.ServiceApiKeyRepository;
import ru.agimate.connectorsapi.service.dto.ServiceApiKeyCreateResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ServiceApiKeyService {

    private static final int MAX_KEYS_PER_USER = 10;

    private final ServiceApiKeyRepository serviceApiKeyRepository;

    @Transactional
    public ServiceApiKeyCreateResult createKey(UUID userPubId, String name, String description) {
        long existingCount = serviceApiKeyRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of API keys reached: " + MAX_KEYS_PER_USER);
        }

        if (serviceApiKeyRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A key with this name already exists");
        }

        GeneratedApiKey generatedKey = ApiKeyUtils.generate("con");

        ServiceApiKey serviceApiKey = ServiceApiKey.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        ServiceApiKey saved = serviceApiKeyRepository.save(serviceApiKey);
        log.info("Created new connector API key for user {}: {}", userPubId, saved.getPubId());

        return new ServiceApiKeyCreateResult(saved, generatedKey.fullKey());
    }

    public Optional<ServiceApiKey> validateKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        ParsedApiKey parsed;
        try {
            parsed = ApiKeyUtils.parse(apiKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid API key format: {}", e.getMessage());
            return Optional.empty();
        }

        if (!ApiKeyUtils.verifyChecksum(parsed)) {
            log.debug("API key checksum verification failed");
            return Optional.empty();
        }

        Optional<ServiceApiKey> keyOpt = serviceApiKeyRepository.findActiveKeyByKeyId(parsed.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        ServiceApiKey key = keyOpt.get();
        if (!ApiKeyUtils.verifySecret(parsed.secret(), key.getKeyHash())) {
            log.debug("API key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }

    @Transactional
    public Optional<ServiceApiKey> validateKeyAndRecordUsage(String apiKey, String clientIp) {
        // For now, just validate without recording usage
        // Can extend later to track last_used_at, usage_count, etc.
        return validateKey(apiKey);
    }

    public List<ServiceApiKey> getKeysForUser(UUID userPubId) {
        return serviceApiKeyRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<ServiceApiKey> getKeyByPubId(UUID pubId, UUID userPubId) {
        return serviceApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public ServiceApiKey updateKey(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        ServiceApiKey key = serviceApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector API key not found"));

        if (name != null) key.setName(name);
        if (description != null) key.setDescription(description);
        if (enabled != null) key.setEnabled(enabled);

        return serviceApiKeyRepository.save(key);
    }

    @Transactional
    public void deleteKey(UUID pubId, UUID userPubId) {
        ServiceApiKey key = serviceApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector API key not found"));

        serviceApiKeyRepository.softDelete(key.getId(), LocalDateTime.now());
        log.info("Soft deleted connector API key: {}", pubId);
    }

    @Transactional
    public ServiceApiKeyCreateResult regenerateKey(UUID pubId, UUID userPubId) {
        ServiceApiKey oldKey = serviceApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector API key not found"));

        serviceApiKeyRepository.softDelete(oldKey.getId(), LocalDateTime.now());

        return createKey(userPubId, oldKey.getName(), oldKey.getDescription());
    }
}
