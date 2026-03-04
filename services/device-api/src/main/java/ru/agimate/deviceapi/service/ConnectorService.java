package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.deviceapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.security.ConnectorSecurityUtils;
import ru.agimate.deviceapi.service.dto.ConnectorCreateResult;
import ru.agimate.deviceapi.util.ConnectorKeyUtils;
import ru.agimate.deviceapi.util.GeneratedConnectorKey;

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
public class ConnectorService {

    private static final int MAX_KEYS_PER_USER = 10;
    public static final String CONNECTOR_KEY_PREFIX = "dvck";

    private final AppRepository appRepository;

    @Transactional
    public ConnectorCreateResult createConnector(UUID userPubId, String name, String description, String connectorCode) {
        long existingCount = appRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
        }

        if (appRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A connector with this name already exists");
        }

        GeneratedConnectorKey generatedKey = ConnectorKeyUtils.generate(CONNECTOR_KEY_PREFIX);

        App app = App.builder()
                .userPubId(userPubId)
                .connectorCode(connectorCode)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        App saved = appRepository.save(app);
        log.info("Created new app for user {}: {}", userPubId, saved.getPubId());

        return new ConnectorCreateResult(saved, generatedKey.fullKey());
    }

    @Transactional
    public App createAppWithCapabilities(
            UUID userPubId,
            String name,
            String description,
            String connectorCode,
            Map<String, Object> triggers,
            Map<String, Object> tools
    ) {
        long existingCount = appRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
        }

        GeneratedConnectorKey generatedKey = ConnectorKeyUtils.generate(CONNECTOR_KEY_PREFIX);

        App app = App.builder()
                .userPubId(userPubId)
                .connectorCode(connectorCode)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .triggers(triggers)
                .tools(tools)
                .enabled(true)
                .build();

        App saved = appRepository.save(app);
        log.info("Created app with capabilities for user {}: {}", userPubId, saved.getPubId());

        return saved;
    }

    public List<App> getConnectorsForUser(UUID userPubId) {
        return appRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<App> getConnectorByPubIdForUser(UUID pubId, UUID userPubId) {
        return appRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public App updateConnector(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        App app = appRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        if (name != null) app.setName(name);
        if (description != null) app.setDescription(description);
        if (enabled != null) app.setEnabled(enabled);

        return appRepository.save(app);
    }

    @Transactional
    public void deleteConnector(UUID pubId, UUID userPubId) {
        App app = appRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        appRepository.softDelete(app.getId(), LocalDateTime.now());
        log.info("Soft deleted app: {}", pubId);
    }

    @Transactional
    public ConnectorCreateResult regenerateConnectorKey(UUID pubId, UUID userPubId) {
        App oldApp = appRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        appRepository.softDelete(oldApp.getId(), LocalDateTime.now());

        return createConnector(userPubId, oldApp.getName(), oldApp.getDescription(), oldApp.getConnectorCode());
    }

    public App getConnectorByPubId(UUID pubId, UUID userPubId) {
        return appRepository.findByPubIdNotDeleted(pubId)
                .filter(a -> a.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
    }

    @Transactional
    public void disconnectConnector(UUID pubId, UUID userPubId) {
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

    public App getConnector() {
        UUID connectorPubId = ConnectorSecurityUtils.getConnectorPubId();
        return appRepository.findByPubId(connectorPubId)
                .orElseThrow(() -> new UnauthorizedStatusException("App not found"));
    }

    public App getConnector(Authentication authentication) {
        var principal = ConnectorSecurityUtils.getPrincipal(authentication);
        return appRepository.findByPubId(principal.connectorPubId())
                .orElseThrow(() -> new UnauthorizedStatusException("App not found"));
    }

    @Transactional
    public App linkDevice(Authentication authentication, LinkDeviceRequest linkDeviceRequest) {
        var app = getConnector(authentication);

        // If already linked to the same device — update capabilities
        if (app.isLinked() && app.getDeviceId().equals(linkDeviceRequest.deviceId())) {
            app.setInfo(buildDeviceFeatures(linkDeviceRequest));
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
        app.setInfo(buildDeviceFeatures(linkDeviceRequest));
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
