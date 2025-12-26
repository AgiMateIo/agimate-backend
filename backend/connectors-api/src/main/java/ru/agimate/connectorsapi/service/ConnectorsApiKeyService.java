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
import ru.agimate.connectorsapi.database.entities.ConnectorsApiKey;
import ru.agimate.connectorsapi.database.repositories.ConnectorsApiKeyRepository;
import ru.agimate.connectorsapi.service.dto.ConnectorApiKeyCreateResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectorsApiKeyService {

    private static final int MAX_KEYS_PER_USER = 10;

    private final ConnectorsApiKeyRepository connectorsApiKeyRepository;

    @Transactional
    public ConnectorApiKeyCreateResult createKey(UUID userPubId, String name, String description) {
        long existingCount = connectorsApiKeyRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of API keys reached: " + MAX_KEYS_PER_USER);
        }

        if (connectorsApiKeyRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A key with this name already exists");
        }

        GeneratedApiKey generatedKey = ApiKeyUtils.generate("con");

        ConnectorsApiKey connectorsApiKey = ConnectorsApiKey.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        ConnectorsApiKey saved = connectorsApiKeyRepository.save(connectorsApiKey);
        log.info("Created new connector API key for user {}: {}", userPubId, saved.getPubId());

        return new ConnectorApiKeyCreateResult(saved, generatedKey.fullKey());
    }

    public Optional<ConnectorsApiKey> validateKey(String apiKey) {
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

        Optional<ConnectorsApiKey> keyOpt = connectorsApiKeyRepository.findActiveKeyByKeyId(parsed.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        ConnectorsApiKey key = keyOpt.get();
        if (!ApiKeyUtils.verifySecret(parsed.secret(), key.getKeyHash())) {
            log.debug("API key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }

    @Transactional
    public Optional<ConnectorsApiKey> validateKeyAndRecordUsage(String apiKey, String clientIp) {
        // For now, just validate without recording usage
        // Can extend later to track last_used_at, usage_count, etc.
        return validateKey(apiKey);
    }

    public List<ConnectorsApiKey> getKeysForUser(UUID userPubId) {
        return connectorsApiKeyRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<ConnectorsApiKey> getKeyByPubId(UUID pubId, UUID userPubId) {
        return connectorsApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public ConnectorsApiKey updateKey(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        ConnectorsApiKey key = connectorsApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector API key not found"));

        if (name != null) key.setName(name);
        if (description != null) key.setDescription(description);
        if (enabled != null) key.setEnabled(enabled);

        return connectorsApiKeyRepository.save(key);
    }

    @Transactional
    public void deleteKey(UUID pubId, UUID userPubId) {
        ConnectorsApiKey key = connectorsApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector API key not found"));

        connectorsApiKeyRepository.softDelete(key.getId(), LocalDateTime.now());
        log.info("Soft deleted connector API key: {}", pubId);
    }

    @Transactional
    public ConnectorApiKeyCreateResult regenerateKey(UUID pubId, UUID userPubId) {
        ConnectorsApiKey oldKey = connectorsApiKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector API key not found"));

        connectorsApiKeyRepository.softDelete(oldKey.getId(), LocalDateTime.now());

        return createKey(userPubId, oldKey.getName(), oldKey.getDescription());
    }
}
