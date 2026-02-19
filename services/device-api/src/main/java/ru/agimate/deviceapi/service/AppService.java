package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.deviceapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.security.AppAuthenticationToken;
import ru.agimate.deviceapi.security.AppPrincipal;
import ru.agimate.deviceapi.service.dto.AppCreateResult;
import ru.agimate.deviceapi.util.ApiKeyUtils;
import ru.agimate.deviceapi.util.GeneratedApiKey;
import ru.agimate.deviceapi.util.ParsedApiKey;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AppService {

    private static final int MAX_KEYS_PER_USER = 10;

    private final AppRepository appRepository;

    @Transactional
    public AppCreateResult createApp(UUID userPubId, String name, String description) {
        long existingCount = appRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of API keys reached: " + MAX_KEYS_PER_USER);
        }

        if (appRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A key with this name already exists");
        }

        GeneratedApiKey generatedKey = ApiKeyUtils.generate("dvck");

        App app = App.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        App saved = appRepository.save(app);
        log.info("Created new app for user {}: {}", userPubId, saved.getPubId());

        return new AppCreateResult(saved, generatedKey.fullKey());
    }

    public Optional<App> validateKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        ParsedApiKey parsedApiKey;
        try {
            parsedApiKey = ApiKeyUtils.parse(apiKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid API key format: {}", e.getMessage());
            return Optional.empty();
        }

        if (!ApiKeyUtils.verifyChecksum(parsedApiKey)) {
            log.debug("API key checksum verification failed");
            return Optional.empty();
        }

        Optional<App> keyOpt = appRepository.findActiveKeyByKeyId(parsedApiKey.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        App key = keyOpt.get();
        if (!ApiKeyUtils.verifySecret(parsedApiKey.secret(), key.getKeyHash())) {
            log.debug("API key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }

    @Transactional
    public Optional<App> validateKeyAndRecordUsage(String apiKey, String clientIp) {
        return validateKey(apiKey);
    }

    public List<App> getKeysForUser(UUID userPubId) {
        return appRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<App> getKeyByPubId(UUID pubId, UUID userPubId) {
        return appRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public App updateKey(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        App key = appRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        if (name != null) key.setName(name);
        if (description != null) key.setDescription(description);
        if (enabled != null) key.setEnabled(enabled);

        return appRepository.save(key);
    }

    @Transactional
    public void deleteKey(UUID pubId, UUID userPubId) {
        App key = appRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        appRepository.softDelete(key.getId(), LocalDateTime.now());
        log.info("Soft deleted app: {}", pubId);
    }

    @Transactional
    public AppCreateResult regenerateKey(UUID pubId, UUID userPubId) {
        App oldKey = appRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        appRepository.softDelete(oldKey.getId(), LocalDateTime.now());

        return createApp(userPubId, oldKey.getName(), oldKey.getDescription());
    }

    public App getAppByPubId(UUID pubId, UUID userPubId) {
        return appRepository.findByPubIdNotDeleted(pubId)
                .filter(a -> a.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
    }

    @Transactional
    public void disconnectApp(UUID pubId, UUID userPubId) {
        App app = appRepository.findByPubIdNotDeleted(pubId)
                .filter(a -> a.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        if (!app.isLinked()) {
            throw new BadRequestStatusException("App is not linked to any device");
        }

        app.disconnect();
        appRepository.save(app);
        log.info("Disconnected app {}", pubId);
    }

    public App getApp() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return getApp(authentication);
    }

    public App getApp(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("API key is not authenticated");
        }

        if (authentication instanceof AppAuthenticationToken appAuthenticationToken) {
            AppPrincipal principal = (AppPrincipal) appAuthenticationToken.getPrincipal();
            return appRepository.findByPubId(principal.appPubId())
                    .orElseThrow(() -> new UnauthorizedStatusException("App not found"));
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }

    @Transactional
    public App linkDevice(Authentication authentication, LinkDeviceRequest linkDeviceRequest) {
        var app = getApp(authentication);

        // If already linked to the same device — update capabilities
        if (app.isLinked() && app.getDeviceId().equals(linkDeviceRequest.deviceId())) {
            app.setDeviceFeatures(buildDeviceFeatures(linkDeviceRequest));
            app.setTriggers(linkDeviceRequest.triggers());
            app.setTools(linkDeviceRequest.tools());
            return appRepository.save(app);
        }

        // If already linked to a different device — conflict
        if (app.isLinked()) {
            log.warn("App {} is already linked to device {}", app.getPubId(), app.getDeviceId());
            return null;
        }

        // Link device
        app.setDeviceId(linkDeviceRequest.deviceId());
        app.setDeviceFeatures(buildDeviceFeatures(linkDeviceRequest));
        app.setTriggers(linkDeviceRequest.triggers());
        app.setTools(linkDeviceRequest.tools());
        app = appRepository.save(app);
        log.info("Linked device {} to app {}", linkDeviceRequest.deviceId(), app.getPubId());

        return app;
    }

    private Map<String, Object> buildDeviceFeatures(LinkDeviceRequest request) {
        var features = request.deviceFeatures() != null
                ? new HashMap<>(request.deviceFeatures())
                : new HashMap<String, Object>();
        if (request.deviceName() != null) features.put("deviceName", request.deviceName());
        if (request.deviceOs() != null) features.put("deviceOs", request.deviceOs());
        return features;
    }
}
