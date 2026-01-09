package ru.agimate.mobileapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.mobileapi.controller.dto.LinkDeviceRequest;
import ru.agimate.mobileapi.database.entities.Device;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;
import ru.agimate.mobileapi.database.repositories.DeviceAuthKeyRepository;
import ru.agimate.mobileapi.database.repositories.DeviceRepository;
import ru.agimate.mobileapi.security.DeviceAuthenticationToken;
import ru.agimate.mobileapi.security.DevicePrincipal;
import ru.agimate.mobileapi.service.dto.DeviceAuthKeyCreateResult;
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
public class DeviceAuthKeyService {

    private static final int MAX_KEYS_PER_USER = 10;

    private final DeviceAuthKeyRepository deviceAuthKeyRepository;
    private final DeviceRepository deviceRepository;

    @Transactional
    public DeviceAuthKeyCreateResult createKey(UUID userPubId, String name, String description) {
        long existingCount = deviceAuthKeyRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of API keys reached: " + MAX_KEYS_PER_USER);
        }

        if (deviceAuthKeyRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A key with this name already exists");
        }

        GeneratedApiKey generatedKey = ApiKeyUtils.generate("mob");

        DeviceAuthKey deviceAuthKey = DeviceAuthKey.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        DeviceAuthKey saved = deviceAuthKeyRepository.save(deviceAuthKey);
        log.info("Created new device auth key for user {}: {}", userPubId, saved.getPubId());

        return new DeviceAuthKeyCreateResult(saved, generatedKey.fullKey());
    }

    public Optional<DeviceAuthKey> validateKey(String apiKey) {
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

        Optional<DeviceAuthKey> keyOpt = deviceAuthKeyRepository.findActiveKeyByKeyId(parsed.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        DeviceAuthKey key = keyOpt.get();
        if (!ApiKeyUtils.verifySecret(parsed.secret(), key.getKeyHash())) {
            log.debug("API key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }

    @Transactional
    public Optional<DeviceAuthKey> validateKeyAndRecordUsage(String apiKey, String clientIp) {
        return validateKey(apiKey);
    }

    public List<DeviceAuthKey> getKeysForUser(UUID userPubId) {
        return deviceAuthKeyRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<DeviceAuthKey> getKeyByPubId(UUID pubId, UUID userPubId) {
        return deviceAuthKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public DeviceAuthKey updateKey(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        DeviceAuthKey key = deviceAuthKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Device auth key not found"));

        if (name != null) key.setName(name);
        if (description != null) key.setDescription(description);
        if (enabled != null) key.setEnabled(enabled);

        return deviceAuthKeyRepository.save(key);
    }

    @Transactional
    public void deleteKey(UUID pubId, UUID userPubId) {
        DeviceAuthKey key = deviceAuthKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Device auth key not found"));

        deviceAuthKeyRepository.softDelete(key.getId(), LocalDateTime.now());
        log.info("Soft deleted device auth key: {}", pubId);
    }

    @Transactional
    public DeviceAuthKeyCreateResult regenerateKey(UUID pubId, UUID userPubId) {
        DeviceAuthKey oldKey = deviceAuthKeyRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Device auth key not found"));

        deviceAuthKeyRepository.softDelete(oldKey.getId(), LocalDateTime.now());

        return createKey(userPubId, oldKey.getName(), oldKey.getDescription());
    }

    public DeviceAuthKey getDeviceAuthKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return getDeviceAuthKey(authentication);
    }

    public DeviceAuthKey getDeviceAuthKey(Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("API key is not authenticated");
        }

        if (authentication instanceof DeviceAuthenticationToken deviceAuthenticationToken) {
            DevicePrincipal principal = (DevicePrincipal) deviceAuthenticationToken.getPrincipal();
            return deviceAuthKeyRepository.findByPubId(principal.deviceAuthPubId())
                    .orElseThrow(() -> new UnauthorizedStatusException("Device not found"));
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }

    @Transactional
    public Device linkDevice(Authentication authentication, LinkDeviceRequest linkDeviceRequest) {
        var deviceAuthKey = getDeviceAuthKey(authentication);

        // Get or create device by deviceId
        Device device = deviceRepository.findByDeviceId(linkDeviceRequest.deviceId())
                .orElseGet(() -> {
                    Device newDevice = Device.builder()
                            .deviceId(linkDeviceRequest.deviceId())
                            .name(linkDeviceRequest.deviceName())
                            .os(linkDeviceRequest.deviceOs())
                            .build();
                    return deviceRepository.save(newDevice);
                });

        // Check if device is already linked to this deviceAuthKey
        if (device.getDeviceAuthKey() != null && device.getDeviceAuthKey().getId().equals(deviceAuthKey.getId())) {
            return device;
        }

        // Check if device is linked to another deviceAuthKey
        if (device.getDeviceAuthKey() != null && !device.getDeviceAuthKey().getId().equals(deviceAuthKey.getId())) {
            log.warn("Device {} is already linked to another auth key", linkDeviceRequest.deviceId());
            return null;
        }

        // Link device to deviceAuthKey
        device.setDeviceAuthKey(deviceAuthKey);
        device = deviceRepository.save(device);
        log.info("Linked device {} to auth key {}", linkDeviceRequest.deviceId(), deviceAuthKey.getPubId());

        return device;
    }
}
