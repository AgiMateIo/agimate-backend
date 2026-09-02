package ru.agimate.controlapi.connectors.internal.platform;

import org.springframework.data.domain.Page;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
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

    /** Accurate flag for Page-backed listings: {@code true} iff rows exist beyond the first page. */
    static boolean truncated(Page<?> page) {
        return page.hasNext();
    }

    /** The outcome of slicing an overflow fetch (fetched with {@code MAX_LISTING + 1}) to the cap. */
    record Capped<T>(List<T> items, boolean truncated) {
    }

    /** Exactly {@link #MAX_LISTING} rows is NOT truncation — only an overflow past the cap is.
     *  Fetch {@code MAX_LISTING + 1} rows, then slice here: the flag stays truthful at exactly 100. */
    static <T> Capped<T> cap(List<T> fetched) {
        return fetched.size() > MAX_LISTING
                ? new Capped<>(fetched.subList(0, MAX_LISTING), true)
                : new Capped<>(fetched, false);
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

    /** Optional ISO local date-time (no timezone suffix), e.g. {@code 2026-09-01T10:00:00} — the
     *  frame the rows are stamped with (the listing's own timestamps are in this format). An
     *  offset-carrying ISO string is refused on purpose: converting it would silently shift the
     *  window against rows written in the server's local clock. */
    static LocalDateTime parseLocalDateTimeOrNull(String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value
                    + "' — expected an ISO local date-time without a timezone suffix, "
                    + "e.g. 2026-09-01T10:00:00 (the format of the timestamps this listing returns)");
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
