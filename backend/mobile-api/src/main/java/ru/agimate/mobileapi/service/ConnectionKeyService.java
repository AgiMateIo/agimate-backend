package ru.agimate.mobileapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.mobileapi.database.entities.ConnectionKey;
import ru.agimate.mobileapi.database.repositories.ConnectionKeyRepository;
import ru.agimate.mobileapi.service.dto.ConnectionKeyCreateResult;
import ru.agimate.mobileapi.util.ApiKeyUtils;
import ru.agimate.mobileapi.util.GeneratedApiKey;
import ru.agimate.mobileapi.util.ParsedApiKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectionKeyService {

    private static final int MAX_KEYS_PER_USER = 10;

    private final ConnectionKeyRepository connectionKeyRepository;

    @Transactional
    public ConnectionKeyCreateResult createKey(UUID userPubId, String name, String description) {
        long existingCount = connectionKeyRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of API keys reached: " + MAX_KEYS_PER_USER);
        }

        if (connectionKeyRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A key with this name already exists");
        }

        GeneratedApiKey generatedKey = ApiKeyUtils.generate("mob");

        ConnectionKey connectionKey = ConnectionKey.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        ConnectionKey saved = connectionKeyRepository.save(connectionKey);
        log.info("Created new connection key for user {}: {}", userPubId, saved.getPubId());

        return new ConnectionKeyCreateResult(saved, generatedKey.fullKey());
    }

    public Optional<ConnectionKey> validateKey(String apiKey) {
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

        Optional<ConnectionKey> keyOpt = connectionKeyRepository.findActiveKeyByKeyId(parsed.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        ConnectionKey key = keyOpt.get();
        if (!ApiKeyUtils.verifySecret(parsed.secret(), key.getKeyHash())) {
            log.debug("API key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }

    @Transactional
    public Optional<ConnectionKey> validateKeyAndRecordUsage(String apiKey, String clientIp) {
        return validateKey(apiKey);
    }

    public List<ConnectionKey> getKeysForUser(UUID userPubId) {
        return connectionKeyRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<ConnectionKey> getKeyByPubId(UUID pubId, UUID userPubId) {
        return connectionKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public ConnectionKey updateKey(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        ConnectionKey key = connectionKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connection key not found"));

        if (name != null) key.setName(name);
        if (description != null) key.setDescription(description);
        if (enabled != null) key.setEnabled(enabled);

        return connectionKeyRepository.save(key);
    }

    @Transactional
    public void deleteKey(UUID pubId, UUID userPubId) {
        ConnectionKey key = connectionKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connection key not found"));

        connectionKeyRepository.softDelete(key.getId(), LocalDateTime.now());
        log.info("Soft deleted connection key: {}", pubId);
    }

    @Transactional
    public ConnectionKeyCreateResult regenerateKey(UUID pubId, UUID userPubId) {
        ConnectionKey oldKey = connectionKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connection key not found"));

        connectionKeyRepository.softDelete(oldKey.getId(), LocalDateTime.now());

        return createKey(userPubId, oldKey.getName(), oldKey.getDescription());
    }
}
