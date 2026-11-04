package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.PageRequests;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.controlapi.connectors.core.FullCodes;
import ru.agimate.controlapi.controller.app.dto.AppDeclaration;
import ru.agimate.controlapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.controlapi.controller.app.dto.SkillDeclaration;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ExecutionKind;
import ru.agimate.controlapi.database.model.AppSkill;
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
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.GeneratedAppKey;
import ru.agimate.controlapi.util.StarterTexts;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final SkillService skillService;

    @Transactional
    public AppCreateResult createApp(UUID userId, String description, String connectorCode) {
        long existingCount = appRepository.countByUserIdNotDeleted(userId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
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
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        App saved = appRepository.save(app);

        // We register the instance in the single connections registry (id = app.id → connectionId never
        // changes). The namespace cannot be built from the app yet — it names itself at link time — so
        // the placeholder is per app: slug("app", null) would collapse to "app_app" and the second
        // unlinked app would collide on (full_code, user_id).
        String placeholder = FullCodes.withSlug(saved.getConnectorCode(), saved.getId().toString());
        connectionRepository.save(Connection.builder()
                .id(saved.getId())
                .connectorCode(saved.getConnectorCode())
                .fullCode(placeholder)
                .userId(userId)
                .appId(saved.getId())
                .build());

        log.info("Created new app for user {}: {}", userId, saved.getId());

        return new AppCreateResult(saved, generatedKey.fullKey());
    }

    public Page<App> getAppsForUser(UUID userId, int page, int size) {
        return appRepository.findByUserIdNotDeleted(userId, PageRequests.of(page, size));
    }

    public App getAppById(UUID id, UUID userId) {
        return appRepository.findByIdNotDeleted(id)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
    }

    @Transactional
    public App updateApp(UUID id, UUID userId, String description, Boolean enabled) {
        App app = appRepository.findByIdNotDeleted(id)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));

        // Partial by design (see the endpoint, which answers to both PUT and PATCH): null is "not sent",
        // and a blank description is the erase — the only field here that has anything to erase.
        if (description != null) app.setDescription(description.isBlank() ? null : description);
        if (enabled != null) app.setEnabled(enabled);

        App saved = appRepository.save(app);

        // The app and its connection carry the same switch: RequirementState looks at the connection's
        // isUsable, so a disabled app whose connection stays enabled would still show OK.
        if (enabled != null) {
            connectionRepository.findByAppIdAndDeletedAtIsNull(app.getId())
                    .ifPresent(connection -> {
                        connection.setEnabled(enabled);
                        connectionRepository.save(connection);
                    });
        }
        return saved;
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
        // The skill the app brought goes with it; an already deleted one is skipped, not an error.
        skillService.deleteOriginSkill(app.getId(), userId);
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
     * A trigger must have been declared by the app at link time (the {@code connection_triggers}
     * catalogue is the instance's source of truth): an undeclared name is a breach of contract, not an
     * event to route. For apps {@code connectionId == app.id}.
     */
    public void requireDeclaredTrigger(App app, String triggerName) {
        if (!connectionTriggerRepository.existsActiveByConnectionIdAndName(app.getId(), triggerName)) {
            throw new BadRequestStatusException(
                    "Trigger '" + triggerName + "' is not declared by this app");
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


    /**
     * Declare the device, its catalogue, and — when the client sends the blocks — the app's identity
     * and the skill it brings. Idempotent by design: the client repeats it at every start, and a
     * broken connection must not cost the app its skill (docs/decisions/app-skills.md).
     *
     * @return the linked app, or {@code null} when the key is already bound to another device
     */
    @Transactional
    public App linkDevice(AppPrincipal principal, LinkDeviceRequest linkDeviceRequest) {
        // Locked for the whole link: the skill is created once per origin, and two concurrent starts of
        // the same app must not both find it missing.
        var app = appRepository.findByIdForUpdate(principal.appId())
                .orElseThrow(() -> new UnauthorizedStatusException("App not found"));

        // If already linked to a different device — conflict, checked before any block so a wrong key
        // gets the same 409 whether or not the request is otherwise valid.
        if (app.isLinked() && !app.getDeviceId().equals(linkDeviceRequest.deviceId())) {
            log.warn("App {} is already linked to device {}", app.getId(), app.getDeviceId());
            return null;
        }

        // The skill name is built from the app code, so the skill block cannot stand alone.
        if (linkDeviceRequest.skill() != null && linkDeviceRequest.app() == null) {
            throw new BadRequestStatusException(
                    "The skill block requires the app block: the skill name is built from app.code");
        }
        String appCode = linkDeviceRequest.app() != null ? normalizeAppCode(linkDeviceRequest.app().code()) : null;
        String appVersion = linkDeviceRequest.app() != null
                ? requireText(linkDeviceRequest.app().version(), "app.version") : null;
        AppSkill declaration = linkDeviceRequest.skill() != null ? toAppSkill(linkDeviceRequest.skill()) : null;

        boolean firstLink = !app.isLinked();
        if (firstLink) {
            app.setDeviceId(linkDeviceRequest.deviceId());
        }
        app.setInfo(buildDeviceFeatures(linkDeviceRequest));
        app.setTriggers(linkDeviceRequest.triggers());
        app.setTools(linkDeviceRequest.tools());
        if (linkDeviceRequest.app() != null) {
            applyIdentity(app, appCode, linkDeviceRequest.app().title(), appVersion);
        }
        if (declaration != null) {
            app.setSkill(declaration);
        }
        App saved = appRepository.save(app);
        syncAppCatalog(saved.getId(), linkDeviceRequest);

        connectionRepository.findByAppIdAndDeletedAtIsNull(saved.getId())
                .ifPresent(connection -> syncConnection(connection, saved, firstLink && linkDeviceRequest.app() != null));

        if (declaration != null && saved.getCode() != null) {
            boolean created = skillService.createFromApp(saved.getUserId(), saved.getId(), saved.getCode(),
                    saved.getTitle(), declaration).isPresent();
            if (!created) {
                // An existing skill is refreshed only while the text is the app's own; the check is
                // inside SkillService, so a user-edited or deleted skill stays as it is.
                skillService.findLiveOriginSkill(saved.getId())
                        .ifPresent(skill -> skillService.applyAppDeclaration(
                                skill, saved.getCode(), saved.getTitle(), declaration));
            }
        }

        log.info("Linked device {} to app {}", linkDeviceRequest.deviceId(), saved.getId());
        return saved;
    }

    /**
     * The app's identity: the code and version first arrive with the {@code app} block. A row created
     * before this release holds the old user label in {@code code}; empty {@code title}/{@code version}
     * mark it, and the first block replaces the label. A later code change is ignored — the code is
     * baked into the tool namespace and the skill name.
     */
    private void applyIdentity(App app, String code, String title, String version) {
        boolean transitional = app.getTitle() == null && app.getVersion() == null;
        if (app.getCode() == null || transitional) {
            app.setCode(code);
        } else if (!app.getCode().equals(code)) {
            log.warn("App {} keeps code '{}'; the declared '{}' is ignored — the namespace and the "
                    + "skill name are built from the first one", app.getId(), app.getCode(), code);
        }
        app.setTitle(blankToNull(title));
        app.setVersion(version);
    }

    /**
     * The connection mirrors what the app declared: the display name follows the title, and the
     * namespace is assigned on the first link carrying the {@code app} block. An already linked
     * connection keeps its namespace — renaming tools would break ABAC rules, call history and skills
     * that reference tool names.
     */
    private void syncConnection(Connection connection, App app, boolean assignNamespace) {
        connection.setName(app.getTitle() != null ? app.getTitle() : app.getCode());
        if (assignNamespace) {
            String slug = FullCodes.slug(SkillService.APP_CONNECTOR_CODE, app.getCode());
            String candidate = FullCodes.withSlug(SkillService.APP_CONNECTOR_CODE, slug);
            int suffix = 2;
            while (connectionRepository.existsByFullCodeAndUserIdAndDeletedAtIsNull(candidate, app.getUserId())) {
                candidate = FullCodes.withSlug(SkillService.APP_CONNECTOR_CODE, slug + "_" + suffix++);
            }
            connection.setSubCode(slug);
            connection.setFullCode(candidate);
        }
        connectionRepository.save(connection);
    }

    private static String normalizeAppCode(String raw) {
        String code = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (code.length() > 48 || !SkillService.NAME_SLUG.matcher(code).matches()) {
            throw new BadRequestStatusException(
                    "app.code must be a kebab-case code (lowercase letters, digits, hyphens), up to 48 "
                            + "characters, got: '" + raw + "'");
        }
        return code;
    }

    private static AppSkill toAppSkill(SkillDeclaration skill) {
        return new AppSkill(
                blankToNull(skill.title()),
                requireText(skill.description(), "skill.description"),
                requireText(skill.body(), "skill.body"),
                StarterTexts.starters(skill.starters(), StarterTexts.MAX_SKILL_STARTERS, "app skill"),
                skill.language());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestStatusException(field + " is required");
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /**
     * Mirrors the app's set of tools and triggers into the normalised
     * {@code connection_tools}/{@code connection_triggers} (for checking the tools and triggers available
     * in channels and policies). A full replacement: the app's discovered set is the instance's only
     * source of truth.
     */
    private void syncAppCatalog(UUID connectionId, LinkDeviceRequest request) {
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
