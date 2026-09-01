package ru.agimate.controlapi.connectors.internal.platform;

import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Static helpers shared by the platform connector's tool-service modules. No Spring bean: everything
 * is pure functions of the current {@link ConnectorEnvHolder} environment or of the passed
 * repositories. Module-private convenience mappers ({@code toXxxBrief}, {@code displayTitle},
 * {@code isSystem}) stay private per module that uses them — tiny and deliberately duplicated so each
 * module file stays independently owned.
 */
final class PlatformToolsSupport {

    /** Cap of every listing: first page only, MCP has no pagination. */
    static final int MAX_LISTING = 100;

    private PlatformToolsSupport() {
    }

    /** The owning user of the current call; throws ConnectorException when env.userId is null
     *  (a global job/webhook must never reach a platform tool). */
    static UUID userId() {
        UUID userId = ConnectorEnvHolder.current().userId();
        if (userId == null) {
            throw new ConnectorException("No user bound to the platform call");
        }
        return userId;
    }

    /** An agent does not manage itself: target == the caller (env.agentId). env.agentId is null
     *  outside a tool-use flow (listing) — null never equals the target, so listings pass. */
    static void requireNotSelf(UUID targetAgentId) {
        UUID self = ConnectorEnvHolder.current().agentId();
        if (self != null && self.equals(targetAgentId)) {
            throw new ConnectorException("An agent cannot manage itself");
        }
    }

    /** Run a domain operation, translating core HTTP exceptions into {@link ConnectorException}. */
    static <T> T domain(Supplier<T> op) {
        try {
            return op.get();
        } catch (BaseHttpStatusException e) {
            throw new ConnectorException(e.getMessage());
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String requireText(String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            throw new ConnectorException("Parameter '" + field + "' is required");
        }
        return v;
    }

    static UUID parseUuid(String value, String field) {
        String v = requireText(value, field);
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value + "'");
        }
    }

    /** Generic parse-or-null for optional id params (e.g. teamId): null/blank → null. */
    static UUID parseUuidOrNull(String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value + "'");
        }
    }

    /** Agent type from a tool param; null → GENERIC. Throws ConnectorException listing the allowed
     *  values on garbage. WEBHOOK is now allowed — webhook params are validated by AgentService
     *  ({@code validateWebhookFields}), not by the connector. */
    static AgentType parseAgentType(String type) {
        String value = blankToNull(type);
        if (value == null) {
            return AgentType.GENERIC;
        }
        try {
            return AgentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid agent type: '" + type + "'. Allowed: "
                    + Arrays.stream(AgentType.values()).map(Enum::name)
                    .collect(Collectors.joining(", ")));
        }
    }

    /** The user's own agent; else ConnectorException("Agent not found: …") — no existence leak. */
    static Agent ownedAgent(AgentRepository agentRepository, UUID id) {
        return agentRepository.findById(id)
                .filter(a -> a.getUserId().equals(userId()))
                .orElseThrow(() -> new ConnectorException("Agent not found: " + id));
    }

    /** Own or public skill; else ConnectorException("Skill not found: …"). */
    static Skill accessibleSkill(SkillRepository skillRepository, UUID id) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new ConnectorException("Skill not found: " + id));
        if (!skill.getUserId().equals(userId()) && !Boolean.TRUE.equals(skill.getIsPublic())) {
            throw new ConnectorException("Skill not found: " + id);
        }
        return skill;
    }

    /**
     * Enum parse with an allowed-values error message. A null or blank value is an error, never a
     * default: callers that want "blank = not sent" resolve it to the current value themselves
     * (update tools), and a blank reaching here would otherwise NPE inside {@code valueOf}.
     */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            throw new ConnectorException("Invalid " + field + ": '" + value
                    + "'. Allowed: " + Arrays.stream(type.getEnumConstants())
                    .map(Enum::name).collect(Collectors.joining(", ")));
        }
        try {
            return Enum.valueOf(type, v.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value
                    + "'. Allowed: " + Arrays.stream(type.getEnumConstants())
                    .map(Enum::name).collect(Collectors.joining(", ")));
        }
    }
}
