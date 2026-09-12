package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * One connector a skill declares it needs — the normalised element of {@code skills.connectors}
 * (JSONB). The short frontmatter form {@code - mcp} is {@code {code: mcp, key: mcp}}; the object form
 * adds what a bare code cannot say: which instance ({@link #params}), and which of its tools
 * ({@link #tools}, {@link #triggers}). See {@code docs/decisions/skill-connector-requirements.md}.
 *
 * @param code     connector code
 * @param key      the requirement's key within the skill — what {@code agent_skill_connections} refers
 *                 to; equals {@code code} unless the skill needs two instances of one connector
 * @param title    caption for the connection wizard; {@code null} — derived on the API layer
 * @param params   non-secret credential-form values the wizard pre-fills; {@code null} for internal
 *                 connectors and for the short form
 * @param tools    access rules over the instance's tools; {@code null} — no rules
 * @param triggers access rules over the instance's triggers; {@code null} — no rules
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorRequirement(String code, String key, String title, Map<String, String> params,
                                   Rules tools, Rules triggers) {

    /**
     * Exactly one of {@link #allow}/{@link #deny} is set. {@code allow} is an allow-list: a binding-wide
     * DENY plus an ALLOW per entry; {@code deny} is a DENY per name on top of default-allow.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rules(List<Rule> allow, List<String> deny) {
        /** Not a bean getter on purpose: Jackson would otherwise serialise it as a third field. */
        public boolean hasAllowList() {
            return allow != null;
        }
    }

    /** @param params the ALLOW row's {@code params_filter}; {@code null} — unconstrained */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String name, Map<String, Object> params) {
    }

    /** The short form: a bare code, keyed by itself. */
    public static ConnectorRequirement of(String code) {
        return new ConnectorRequirement(code, code, null, null, null, null);
    }

    public static List<ConnectorRequirement> ofCodes(List<String> codes) {
        return codes.stream().map(ConnectorRequirement::of).toList();
    }

    /** Distinct codes in declaration order — what the code-level readers (presets, run context) want. */
    public static List<String> codes(List<ConnectorRequirement> requirements) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (ConnectorRequirement requirement : requirements) {
            codes.add(requirement.code());
        }
        return new ArrayList<>(codes);
    }

    public boolean hasRules() {
        return tools != null || triggers != null;
    }
}
