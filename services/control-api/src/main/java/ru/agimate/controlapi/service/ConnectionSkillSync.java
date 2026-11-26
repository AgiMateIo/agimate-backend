package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.controller.app.dto.SkillDeclaration;
import ru.agimate.controlapi.database.entities.ConnectionSkill;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.enums.SkillUpdatedBy;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.repositories.ConnectionSkillRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.util.StarterTexts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only writer of {@code connection_skills} and the user copies made from them. Both the app link
 * and app deletion go through it, so the lifecycle rules live in one place: a missing key freezes its
 * copy instead of deleting it, a user-deleted copy is never resurrected, and an existing legacy origin
 * copy is adopted instead of duplicated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConnectionSkillSync {

    /** An app cannot flood the user's catalogue; the number is a product limit, not a storage one. */
    public static final int MAX_SKILLS = 32;
    public static final String LEGACY_KEY = "default";

    private final ConnectionSkillRepository connectionSkillRepository;
    private final SkillRepository skillRepository;
    private final SkillService skillService;

    /**
     * Reconcile one connection's declared catalogue with the stored one.
     *
     * @param fullReplacement {@code true} for the {@code skills} map: keys absent from it are
     *                        soft-deleted. The legacy {@code skill} block passes {@code false} and
     *                        updates only {@code default}.
     */
    public void sync(UUID connectionId, UUID userId, String appCode, String appTitle,
                     Map<String, SkillDeclaration> declarations, boolean fullReplacement) {
        if (declarations == null) {
            return;
        }
        if (declarations.size() > MAX_SKILLS) {
            throw new BadRequestStatusException(
                    "At most " + MAX_SKILLS + " skills per app, got " + declarations.size());
        }

        List<ConnectionSkill> existing = connectionSkillRepository.findByConnectionIdOrderByNameAsc(connectionId);
        Map<String, ConnectionSkill> byUri = new LinkedHashMap<>();
        existing.forEach(skill -> byUri.put(skill.getUri(), skill));

        Set<String> declaredUris = declarations.keySet().stream()
                .map(key -> uri(connectionId, key))
                .collect(Collectors.toSet());
        if (!fullReplacement) {
            // The legacy singular block updates only default; it must not push an app over the cap.
            long liveOthers = existing.stream()
                    .filter(skill -> !skill.isDeleted() && !declaredUris.contains(skill.getUri()))
                    .count();
            if (liveOthers + declarations.size() > MAX_SKILLS) {
                throw new BadRequestStatusException(
                        "At most " + MAX_SKILLS + " skills per app, got " + (liveOthers + declarations.size()));
            }
        }
        for (Map.Entry<String, SkillDeclaration> entry : declarations.entrySet()) {
            String key = entry.getKey();
            validateKey(key);
            SkillDeclaration declaration = entry.getValue();
            validateDeclaration(key, declaration);

            String uri = uri(connectionId, key);
            declaredUris.add(uri);
            ConnectionSkill connectionSkill = byUri.get(uri);
            if (connectionSkill == null) {
                connectionSkill = ConnectionSkill.builder()
                        .connectionId(connectionId)
                        .uri(uri)
                        .name(key)
                        .build();
            }
            applyDeclaration(connectionSkill, key, declaration);
            connectionSkill = connectionSkillRepository.save(connectionSkill);
            ensureCopy(connectionSkill, userId, appCode, appTitle, LEGACY_KEY.equals(key));
        }

        if (fullReplacement) {
            LocalDateTime now = LocalDateTime.now();
            for (ConnectionSkill connectionSkill : existing) {
                if (!connectionSkill.isDeleted() && !declaredUris.contains(connectionSkill.getUri())) {
                    connectionSkill.setDeletedAt(now);
                    connectionSkillRepository.save(connectionSkill);
                }
            }
        }
    }

    /**
     * Delete the connection's declarations and their copies. A copy already deleted by the user is
     * skipped: {@link SkillService#delete} answers 404 for it and would roll back the whole app
     * deletion.
     */
    public void deleteForConnection(UUID connectionId, UUID userId) {
        List<ConnectionSkill> connectionSkills =
                connectionSkillRepository.findByConnectionIdOrderByNameAsc(connectionId);

        skillRepository.findByOriginConnectionId(connectionId)
                .filter(copy -> copy.getConnectionSkillId() == null && !copy.isDeleted())
                .ifPresent(copy -> skillService.delete(copy.getId(), userId, false));

        LocalDateTime now = LocalDateTime.now();
        for (ConnectionSkill connectionSkill : connectionSkills) {
            skillRepository.findByConnectionSkillId(connectionSkill.getId())
                    .filter(copy -> !copy.isDeleted())
                    .ifPresent(copy -> skillService.delete(copy.getId(), userId, false));
            if (!connectionSkill.isDeleted()) {
                connectionSkill.setDeletedAt(now);
                connectionSkillRepository.save(connectionSkill);
            }
        }
    }

    private void ensureCopy(ConnectionSkill connectionSkill, UUID userId, String appCode, String appTitle,
                            boolean legacyDefault) {
        Skill copy = skillRepository.findByConnectionSkillId(connectionSkill.getId()).orElse(null);
        if (copy == null && legacyDefault) {
            copy = skillRepository.findByOriginConnectionId(connectionSkill.getConnectionId()).orElse(null);
            if (copy != null) {
                // Adoption: an old writer created this copy before the declaration existed, or the
                // backfill missed it. Linking it keeps the name and the user's bindings intact.
                copy.setConnectionSkillId(connectionSkill.getId());
                copy = skillRepository.save(copy);
            }
        }

        if (copy != null) {
            if (copy.isDeleted() || copy.getUpdatedBy() == SkillUpdatedBy.USER) {
                return;
            }
            refresh(copy, connectionSkill, appTitle, appCode, legacyDefault);
            return;
        }

        Skill created = Skill.builder()
                .name(availableName(userId, baseName(appCode, connectionSkill.getName(), legacyDefault)))
                .title(title(connectionSkill, appTitle, appCode, legacyDefault))
                .description(connectionSkill.getDescription())
                .mdContent(connectionSkill.getContent())
                .connectors(List.of(ConnectorRequirement.of(SkillService.APP_CONNECTOR_CODE)))
                .disclosure(Disclosure.LAZY)
                .category(ContentCategory.PLATFORM)
                .starters(new ArrayList<>(connectionSkill.getStarters()))
                .userId(userId)
                .isPublic(false)
                .connectionSkillId(connectionSkill.getId())
                .originConnectionId(legacyDefault ? connectionSkill.getConnectionId() : null)
                .updatedBy(SkillUpdatedBy.APP)
                .build();
        skillRepository.save(created);
        log.info("Created app skill copy '{}' for connection skill {}", created.getName(), connectionSkill.getId());
    }

    private void refresh(Skill copy, ConnectionSkill connectionSkill, String appTitle, String appCode,
                         boolean legacyDefault) {
        copy.setTitle(title(connectionSkill, appTitle, appCode, legacyDefault));
        copy.setDescription(connectionSkill.getDescription());
        copy.setMdContent(connectionSkill.getContent());
        copy.setStarters(new ArrayList<>(connectionSkill.getStarters()));
        copy.setVersion((copy.getVersion() == null ? 0 : copy.getVersion()) + 1);
        if (legacyDefault) {
            copy.setOriginConnectionId(connectionSkill.getConnectionId());
        }
        skillRepository.save(copy);
        log.info("Refreshed app skill copy '{}' version={}", copy.getName(), copy.getVersion());
    }

    private String availableName(UUID userId, String base) {
        String candidate = base;
        int suffix = 2;
        while (skillRepository.existsByKeyNotDeleted(userId, candidate, null)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static String baseName(String appCode, String key, boolean legacyDefault) {
        return legacyDefault ? "app-" + appCode : "app-" + appCode + "-" + key;
    }

    private static String title(ConnectionSkill connectionSkill, String appTitle, String appCode,
                                boolean legacyDefault) {
        if (connectionSkill.getTitle() != null && !connectionSkill.getTitle().isBlank()) {
            return connectionSkill.getTitle();
        }
        if (appTitle != null && !appTitle.isBlank()) {
            return appTitle;
        }
        return legacyDefault && appCode != null ? appCode : connectionSkill.getName();
    }

    private static void applyDeclaration(ConnectionSkill connectionSkill, String key, SkillDeclaration declaration) {
        connectionSkill.setName(key);
        connectionSkill.setTitle(blankToNull(declaration.title()));
        connectionSkill.setDescription(declaration.description().strip());
        connectionSkill.setStarters(new ArrayList<>(StarterTexts.starters(
                declaration.starters(), StarterTexts.MAX_SKILL_STARTERS, "skill " + key)));
        connectionSkill.setLanguage(declaration.language() != null ? declaration.language().name() : null);
        connectionSkill.setFrontmatter(frontmatter(key, declaration));
        connectionSkill.setContent(declaration.body().strip());
        byte[] content = connectionSkill.getContent().getBytes(StandardCharsets.UTF_8);
        connectionSkill.setContentSize((long) content.length);
        connectionSkill.setContentDigest(digest(content));
        connectionSkill.setDiscoveredAt(LocalDateTime.now());
        connectionSkill.setDeletedAt(null);
    }

    private static Map<String, Object> frontmatter(String key, SkillDeclaration declaration) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", key);
        frontmatter.put("title", blankToNull(declaration.title()));
        frontmatter.put("description", declaration.description());
        frontmatter.put("starters", declaration.starters() == null ? List.of() : declaration.starters());
        frontmatter.put("language", declaration.language() != null ? declaration.language().name() : null);
        return frontmatter;
    }

    private static void validateKey(String key) {
        if (key == null || key.length() > 64 || !SkillService.NAME_SLUG.matcher(key).matches()) {
            throw new BadRequestStatusException(
                    "Skill key must be kebab-case, up to 64 characters, got: '" + key + "'");
        }
    }

    private static void validateDeclaration(String key, SkillDeclaration declaration) {
        if (declaration == null) {
            throw new BadRequestStatusException("Skill '" + key + "' has no declaration");
        }
        if (declaration.description() == null || declaration.description().isBlank()) {
            throw new BadRequestStatusException("skill " + key + ": description is required");
        }
        if (declaration.body() == null || declaration.body().isBlank()) {
            throw new BadRequestStatusException("skill " + key + ": body is required");
        }
    }

    private static String uri(UUID connectionId, String key) {
        return "app://" + connectionId + "/" + key;
    }

    private static String digest(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
