package ru.agimate.controlapi.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.controller.mcp.dto.McpSkillEntry;
import ru.agimate.controlapi.controller.mcp.dto.McpSkillResource;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.runcontext.RunCatalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The agent's satisfied skills as Agent Skills entries: a synthesized {@code SKILL.md}, its manifest
 * and the bytes {@code resources/read} will serve. The gate is {@link RunCatalog#skillsForAgent}, so
 * an MCP client never sees a skill whose tools the agent does not have.
 *
 * <p>The body is read from the skill row on every request: a digest computed from a stored copy would
 * drift from the bytes actually served. Bodies are small and an agent has few skills, so the fresh
 * read is cheaper than a cache invalidation path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpSkillCatalog {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    private final RunCatalog runCatalog;
    private final SkillRepository skillRepository;

    /** One skill and the exact {@code SKILL.md} bytes its manifest describes. */
    public record Entry(McpSkillEntry skill, String content) {
    }

    /** One {@code skills/list} page; {@code nextCursor} is the next offset or {@code null}. */
    public record Page(List<Entry> entries, String nextCursor) {
    }

    /** A page of the agent's skills, in a stable order by skill id. */
    public Page forAgent(UUID agentId, int offset) {
        List<Entry> all = entries(agentId);
        int from = Math.max(0, Math.min(offset, all.size()));
        int to = Math.min(from + PAGE_SIZE, all.size());
        String next = to < all.size() ? String.valueOf(to) : null;
        return new Page(List.copyOf(all.subList(from, to)), next);
    }

    /** Every entry, for the base resources listing, which has no cursor here. */
    public List<Entry> all(UUID agentId) {
        return entries(agentId);
    }

    /** The entry with exactly this URI, or empty — the same catalogue {@link #forAgent} lists. */
    public Optional<Entry> find(UUID agentId, String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        return entries(agentId).stream()
                .filter(entry -> entry.skill().uri().equals(uri))
                .findFirst();
    }

    private List<Entry> entries(UUID agentId) {
        List<AgentSkillWithConnectorsResponse> bound = runCatalog.skillsForAgent(agentId).stream()
                // The repository promises no order; the suffix assignment must not swap URIs between
                // two requests that list the same skills.
                .sorted(java.util.Comparator.comparing(AgentSkillWithConnectorsResponse::skillId))
                .toList();
        if (bound.isEmpty()) {
            return List.of();
        }
        Map<UUID, Skill> rows = new LinkedHashMap<>();
        skillRepository.findAllById(bound.stream().map(AgentSkillWithConnectorsResponse::skillId).toList())
                .stream()
                .filter(skill -> !skill.isDeleted())
                .forEach(skill -> rows.put(skill.getId(), skill));

        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (AgentSkillWithConnectorsResponse skill : bound) {
            Skill row = rows.get(skill.skillId());
            if (row == null) {
                continue;
            }
            String baseName = normalizeName(row.getName());
            if (names.contains(baseName)) {
                log.warn("MCP skill name '{}' collides for agent {}; suffixing the later one",
                        baseName, agentId);
            }
            String name = uniqueName(baseName, names);
            String description = description(skill, row);
            String content = render(name, description, row.getMdContent());
            String uri = "skill://" + name + "/SKILL.md";
            McpSkillResource manifest = new McpSkillResource(
                    uri, digest(content), content.getBytes(StandardCharsets.UTF_8).length);
            Map<String, Object> frontmatter = new LinkedHashMap<>();
            frontmatter.put("name", name);
            frontmatter.put("description", description);
            entries.add(new Entry(new McpSkillEntry(uri, frontmatter, List.of(manifest)), content));
        }
        return entries;
    }

    /** A skill name is already kebab-case in the catalogue; this is the wire-safe fallback. */
    private static String normalizeName(String raw) {
        String base = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (base.isEmpty()) {
            base = "skill";
        }
        if (base.length() > MAX_NAME_LENGTH) {
            String tail = Integer.toHexString(base.hashCode());
            int keep = MAX_NAME_LENGTH - tail.length() - 1;
            String prefix = base.substring(0, keep).replaceAll("-+$", "");
            base = prefix + "-" + tail;
        }
        return base;
    }

    /**
     * Names are labels in the extension and may collide even inside one server. The suffix keeps both
     * reachable by URI; the URI, not the name, is the identity.
     */
    private static String uniqueName(String base, Set<String> used) {
        String name = base;
        for (int suffix = 2; !used.add(name); suffix++) {
            String tail = "-" + suffix;
            int keep = Math.min(base.length(), MAX_NAME_LENGTH - tail.length());
            name = base.substring(0, keep).replaceAll("-+$", "") + tail;
        }
        return name;
    }

    private static String description(AgentSkillWithConnectorsResponse skill, Skill row) {
        String value = skill.description();
        if (value == null || value.isBlank()) {
            value = row.getTitle();
        }
        if (value == null || value.isBlank()) {
            value = skill.skillName();
        }
        if (value == null || value.isBlank()) {
            value = "skill";
        }
        return truncate(value.replace("\r\n", "\n").replace('\r', '\n'), MAX_DESCRIPTION_LENGTH);
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String render(String name, String description, String body) {
        String content = "---\nname: " + quoted(name) + "\ndescription: " + quoted(description)
                + "\n---\n\n" + (body == null ? "" : body);
        return content.endsWith("\n") ? content : content + "\n";
    }

    /** A YAML double-quoted scalar; the parsed value is exactly the string, newlines included. */
    private static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // YAML forbids literal C0/C1 controls; U+2028 and U+2029 are line breaks to
                    // YAML 1.1 consumers, so they must travel as escapes too.
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String digest(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
