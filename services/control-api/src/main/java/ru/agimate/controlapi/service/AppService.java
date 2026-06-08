package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.controlapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.security.AppSecurityUtils;
import ru.agimate.controlapi.service.dto.AppCreateResult;
import ru.agimate.controlapi.service.dto.AppTool;
import ru.agimate.controlapi.service.dto.AppTrigger;
import ru.agimate.controlapi.util.AppKeyUtils;
import ru.agimate.controlapi.util.GeneratedAppKey;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AppService {

    private static final int MAX_KEYS_PER_USER = 10;
    public static final String APP_KEY_PREFIX = "appk";

    private final AppRepository appRepository;

    @Transactional
    public AppCreateResult createApp(UUID userId, String name, String description, String connectorCode) {
        long existingCount = appRepository.countByUserIdNotDeleted(userId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
        }

        if (appRepository.existsByUserIdAndName(userId, name)) {
            throw new ConflictStatusException("An app with this name already exists");
        }

        GeneratedAppKey generatedKey = AppKeyUtils.generate(APP_KEY_PREFIX);

        // todo: check connectorCode by using connector repository and check coonector.type

        App app = App.builder()
                .userId(userId)
                .connectorCode(connectorCode)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        App saved = appRepository.save(app);
        log.info("Created new app for user {}: {}", userId, saved.getId());

        return new AppCreateResult(saved, generatedKey.fullKey());
    }

    public Page<App> getAppsForUser(UUID userId, int page, int size) {
        return appRepository.findByUserIdNotDeleted(userId, PageRequest.of(page, size));
    }

    public App getAppById(UUID id, UUID userId) {
        return appRepository.findByIdNotDeleted(id)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
    }

    @Transactional
    public App updateApp(UUID id, UUID userId, String name, String description, Boolean enabled) {
        App app = appRepository.findByIdNotDeleted(id)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        if (name != null) app.setName(name);
        if (description != null) app.setDescription(description);
        if (enabled != null) app.setEnabled(enabled);

        return appRepository.save(app);
    }

    @Transactional
    public void deleteApp(UUID id, UUID userId) {
        App app = appRepository.findByIdNotDeleted(id)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        appRepository.softDelete(app.getId(), LocalDateTime.now());
        log.info("Soft deleted app: {}", id);
    }

    @Transactional
    public AppCreateResult regenerateAppKey(UUID id, UUID userId) {
        App app = appRepository.findByIdNotDeleted(id)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        GeneratedAppKey generatedKey = AppKeyUtils.generate(APP_KEY_PREFIX);
        app.setKeyHash(generatedKey.secretHash());
        app.setKeyId(generatedKey.keyId());

        App saved = appRepository.save(app);
        log.info("Regenerated key for app: {}", id);

        return new AppCreateResult(saved, generatedKey.fullKey());
    }

    public App getApp() {
        UUID appId = AppSecurityUtils.getAppId();
        return appRepository.findById(appId)
                .orElseThrow(() -> new UnauthorizedStatusException("App not found"));
    }

    public App getApp(Authentication authentication) {
        var principal = AppSecurityUtils.getPrincipal(authentication);
        return appRepository.findById(principal.appId())
                .orElseThrow(() -> new UnauthorizedStatusException("App not found"));
    }

    public List<AppTool> getToolsByAppIdAndUser(UUID appId, UUID userId) {
        var app = appRepository.findByIdAndUserIdNotDeleted(appId, userId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return parseTools(app.getTools());
    }

    public List<AppTrigger> getTriggersByAppIdAndUser(UUID appId, UUID userId) {
        var app = appRepository.findByIdAndUserIdNotDeleted(appId, userId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return parseTriggers(app.getTriggers());
    }


    @SuppressWarnings("unchecked")
    private List<AppTool> parseTools(Map<String, Object> tools) {
        if (tools == null) return List.of();
        return tools.entrySet().stream()
                .map(entry -> {
                    var value = (Map<String, Object>) entry.getValue();
                    var description = value.getOrDefault("description", "").toString();
                    var params = value.get("params") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.<String>of();
                    return new AppTool(entry.getKey(), description, params);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<AppTrigger> parseTriggers(Map<String, Object> triggers) {
        if (triggers == null) return List.of();
        return triggers.entrySet().stream()
                .map(entry -> {
                    var value = (Map<String, Object>) entry.getValue();
                    var description = value.getOrDefault("description", "").toString();
                    var params = value.get("params") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.<String>of();
                    return new AppTrigger(entry.getKey(), description, params);
                })
                .toList();
    }


    @Transactional
    public App linkDevice(Authentication authentication, LinkDeviceRequest linkDeviceRequest) {
        var app = getApp(authentication);

        // If already linked to the same device — update capabilities
        if (app.isLinked() && app.getDeviceId().equals(linkDeviceRequest.deviceId())) {
            app.setInfo(buildDeviceFeatures(linkDeviceRequest));
            app.setTriggers(linkDeviceRequest.triggers());
            app.setTools(linkDeviceRequest.tools());
            return appRepository.save(app);
        }

        // If already linked to a different device — conflict
        if (app.isLinked()) {
            log.warn("App {} is already linked to device {}", app.getId(), app.getDeviceId());
            return null;
        }

        // Link device
        app.setDeviceId(linkDeviceRequest.deviceId());
        app.setInfo(buildDeviceFeatures(linkDeviceRequest));
        app.setTriggers(linkDeviceRequest.triggers());
        app.setTools(linkDeviceRequest.tools());
        app = appRepository.save(app);
        log.info("Linked device {} to app {}", linkDeviceRequest.deviceId(), app.getId());

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
