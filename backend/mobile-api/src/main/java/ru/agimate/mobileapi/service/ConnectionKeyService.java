package ru.agimate.mobileapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.mobileapi.database.entities.ConnectionKey;
import ru.agimate.mobileapi.database.repositories.ConnectionKeyRepository;
import ru.agimate.mobileapi.service.dto.ConnectionKeyCreateResult;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectionKeyService {

    private static final String KEY_PREFIX = "agm_";
    private static final int KEY_LENGTH = 32;
    private static final int MAX_KEYS_PER_USER = 10;

    private final ConnectionKeyRepository connectionKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ConnectionKeyCreateResult createKey(UUID userPubId, String name, String description,
                                               Integer requestsPerHour, LocalDateTime expiresAt,
                                               String ipWhitelist) {
        long existingCount = connectionKeyRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of API keys reached: " + MAX_KEYS_PER_USER);
        }

        if (connectionKeyRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A key with this name already exists");
        }

        String plaintextKey = generateSecureKey();
        String keyHash = passwordEncoder.encode(plaintextKey);
        String keyPrefix = plaintextKey.substring(0, 8);

        ConnectionKey connectionKey = ConnectionKey.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(keyHash)
                .keyPrefix(keyPrefix)
                .enabled(true)
                .requestsPerHour(requestsPerHour)
                .expiresAt(expiresAt)
                .ipWhitelist(ipWhitelist)
                .usageCount(0L)
                .build();

        ConnectionKey saved = connectionKeyRepository.save(connectionKey);
        log.info("Created new connection key for user {}: {}", userPubId, saved.getPubId());

        return new ConnectionKeyCreateResult(saved, plaintextKey);
    }

    private String generateSecureKey() {
        byte[] randomBytes = new byte[KEY_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return KEY_PREFIX + encoded;
    }

    public Optional<ConnectionKey> validateKey(String apiKey) {
        if (apiKey == null || !apiKey.startsWith(KEY_PREFIX) || apiKey.length() < 12) {
            return Optional.empty();
        }

        String prefix = apiKey.substring(0, 8);
        LocalDateTime now = LocalDateTime.now();

        List<ConnectionKey> candidates = connectionKeyRepository.findActiveKeysByPrefix(prefix, now);

        for (ConnectionKey candidate : candidates) {
            if (passwordEncoder.matches(apiKey, candidate.getKeyHash())) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    @Transactional
    public Optional<ConnectionKey> validateKeyAndRecordUsage(String apiKey, String clientIp) {
        Optional<ConnectionKey> keyOpt = validateKey(apiKey);

        if (keyOpt.isPresent()) {
            ConnectionKey key = keyOpt.get();

            if (key.getIpWhitelist() != null && !key.getIpWhitelist().isBlank()) {
                if (!isIpAllowed(clientIp, key.getIpWhitelist())) {
                    log.warn("API key {} used from non-whitelisted IP: {}", key.getPubId(), clientIp);
                    return Optional.empty();
                }
            }

            connectionKeyRepository.incrementUsage(key.getId(), LocalDateTime.now());
            return keyOpt;
        }

        return Optional.empty();
    }

    private boolean isIpAllowed(String clientIp, String ipWhitelist) {
        if (clientIp == null) return false;
        String[] allowed = ipWhitelist.split(",");
        for (String ip : allowed) {
            if (ip.trim().equals(clientIp)) {
                return true;
            }
        }
        return false;
    }

    public List<ConnectionKey> getKeysForUser(UUID userPubId) {
        return connectionKeyRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<ConnectionKey> getKeyByPubId(UUID pubId, UUID userPubId) {
        return connectionKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public ConnectionKey updateKey(UUID pubId, UUID userPubId, String name, String description,
                                   Boolean enabled, Integer requestsPerHour,
                                   LocalDateTime expiresAt, String ipWhitelist) {
        ConnectionKey key = connectionKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connection key not found"));

        if (name != null) key.setName(name);
        if (description != null) key.setDescription(description);
        if (enabled != null) key.setEnabled(enabled);
        if (requestsPerHour != null) key.setRequestsPerHour(requestsPerHour);
        if (expiresAt != null) key.setExpiresAt(expiresAt);
        if (ipWhitelist != null) key.setIpWhitelist(ipWhitelist);

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

        return createKey(
                userPubId,
                oldKey.getName(),
                oldKey.getDescription(),
                oldKey.getRequestsPerHour(),
                oldKey.getExpiresAt(),
                oldKey.getIpWhitelist()
        );
    }
}
