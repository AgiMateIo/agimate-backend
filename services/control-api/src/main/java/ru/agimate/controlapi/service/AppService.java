package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.controlapi.connectors.core.FullCodes;
import ru.agimate.controlapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ExecutionKind;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
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
    private final ConnectionRepository connectionRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectionBindingService connectionBindingService;

    @Transactional
    public AppCreateResult createApp(UUID userId, String name, String description, String connectorCode) {
        long existingCount = appRepository.countByUserIdNotDeleted(userId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
        }

        if (appRepository.existsByUserIdAndName(userId, name)) {
            throw new ConflictStatusException("An app with this name already exists");
        }

        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new BadRequestStatusException("Unknown connector: " + connectorCode));
        if (connector.getExecutionKind() != ExecutionKind.APP) {
            throw new BadRequestStatusException(
                    "Connector '" + connectorCode + "' does not support app instances");
        }

        GeneratedAppKey generatedKey = AppKeyUtils.generate(APP_KEY_PREFIX);

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

        // We register the instance in the single connections registry (id = app.id → connectionId never changes).
        connectionRepository.save(Connection.builder()
                .id(saved.getId())
                .connectorCode(saved.getConnectorCode())
                .subCode(FullCodes.slug(saved.getConnectorCode(), saved.getName()))
                .fullCode(FullCodes.fullCode(saved.getConnectorCode(), saved.getName()))
                .userId(userId)
                .name(saved.getName())
                .appId(saved.getId())
                .build());

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

        LocalDateTime now = LocalDateTime.now();
        appRepository.softDelete(app.getId(), now);
        connectionRepository.findByAppIdAndDeletedAtIsNull(app.getId())
                .ifPresent(c -> {
                    // A cascade: drop the agents' bindings and policies onto this instance, then collapse the connection.
                    connectionBindingService.detachConnection(c.getId());
                    connectionRepository.softDelete(c.getId(), now);
                });
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

    public App getApp(AppPrincipal principal) {
        return appRepository.findById(principal.appId())
                .orElseThrow(() -> new UnauthorizedStatusException("App not found"));
    }

    /**
     * A trigger must have been declared by the device at link time (the {@code connection_triggers}
     * catalogue is the instance's source of truth): an undeclared name is a breach of contract, not an
     * event to route. For devices {@code connectionId == app.id}.
     */
    public void requireDeclaredTrigger(App app, String triggerName) {
        if (!connectionTriggerRepository.existsActiveByConnectionIdAndName(app.getId(), triggerName)) {
            throw new BadRequestStatusException(
                    "Trigger '" + triggerName + "' is not declared by this device");
        }
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
                    return new AppTool(
                            entry.getKey(),
                            asString(value.get("title")),
                            description,
                            deriveParams(value.get("params"), value.get("inputSchema")),
                            value.get("inputSchema"),
                            value.get("outputSchema"),
                            value.get("annotations"));
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
                    return new AppTrigger(
                            entry.getKey(),
                            asString(value.get("title")),
                            description,
                            deriveParams(value.get("params"), value.get("paramsSchema")),
                            value.get("paramsSchema"));
                })
                .toList();
    }

    /** Parameter names for the UI: the explicit {@code params} list, else the schema's {@code properties} keys, else empty. */
    @SuppressWarnings("unchecked")
    private List<String> deriveParams(Object params, Object schema) {
        if (params instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (schema instanceof Map<?, ?> map && map.get("properties") instanceof Map<?, ?> props) {
            return props.keySet().stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }


    @Transactional
    public App linkDevice(AppPrincipal principal, LinkDeviceRequest linkDeviceRequest) {
        var app = getApp(principal);

        // If already linked to the same device — update capabilities
        if (app.isLinked() && app.getDeviceId().equals(linkDeviceRequest.deviceId())) {
            app.setInfo(buildDeviceFeatures(linkDeviceRequest));
            app.setTriggers(linkDeviceRequest.triggers());
            app.setTools(linkDeviceRequest.tools());
            App saved = appRepository.save(app);
            syncDeviceCatalog(saved.getId(), linkDeviceRequest);
            return saved;
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
        syncDeviceCatalog(app.getId(), linkDeviceRequest);
        log.info("Linked device {} to app {}", linkDeviceRequest.deviceId(), app.getId());

        return app;
    }

    /**
     * Mirrors the device's set of tools and triggers into the normalised
     * {@code connection_tools}/{@code connection_triggers} (for checking the tools and triggers available
     * in channels and policies). A full replacement: the device's discovered set is the instance's only
     * source of truth.
     */
    private void syncDeviceCatalog(UUID connectionId, LinkDeviceRequest request) {
        connectionToolRepository.deleteByConnectionId(connectionId);
        connectionTriggerRepository.deleteByConnectionId(connectionId);

        if (request.tools() != null) {
            request.tools().forEach((name, descriptor) -> {
                if (name == null || name.isBlank()) return;
                connectionToolRepository.save(
                        AppCatalogMapper.toolEntity(connectionId, name, JsonUtils.MAPPER.valueToTree(descriptor)));
            });
        }
        if (request.triggers() != null) {
            request.triggers().forEach((name, descriptor) -> {
                if (name == null || name.isBlank()) return;
                connectionTriggerRepository.save(
                        AppCatalogMapper.triggerEntity(connectionId, name, JsonUtils.MAPPER.valueToTree(descriptor)));
            });
        }
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
