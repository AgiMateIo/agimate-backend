package ru.agimate.controlapi.connectors.internal.skillloader;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolMeta;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.toolloader.ToolLoaderToolService;
import ru.agimate.controlapi.service.runcontext.RunCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code load_skill}: the bodies of the agent's skills, by the names the model read in the skills
 * listing. The scope is the run's catalogue, recomputed here from {@code env.runId} — only a skill
 * that is bound and satisfied can be loaded. The body goes to the model as the tool's result and
 * stays in the session history whole ({@code _meta} marks it as skill material, so the history
 * assembler does not cut it to the tool-result cap).
 */
@Component
@RequiredArgsConstructor
public class SkillLoaderToolService {

    public static final String MATERIAL_SKILL = "skill";

    private final RunCatalog runCatalog;

    @Tool(name = "load_skill", description = "Load the full instructions of skills marked "
            + "'disclosure: lazy' in the skills listing. Pass every skill you are going to need in one call.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            meta = @ToolMeta(key = ToolLoaderToolService.META_CONTEXT_MATERIAL, value = MATERIAL_SKILL))
    public Map<String, Object> loadSkill(
            @ToolParam("Skill names exactly as listed under skills (the name field), e.g. [\"platform\"]")
            List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new ConnectorException("names is required: the skill names from the skills listing");
        }
        RunCatalog.Catalog catalog = catalog(ConnectorEnvHolder.current());
        List<Map<String, Object>> skills = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            String body = runCatalog.skillBody(catalog, name).orElse(null);
            if (body == null) {
                unknown.add(name);
                continue;
            }
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("name", name);
            skill.put("body", body);
            skills.add(skill);
        }
        if (skills.isEmpty()) {
            throw new ConnectorException("Unknown skills: " + String.join(", ", unknown)
                    + ". Skills you can load: " + String.join(", ", runCatalog.skillNames(catalog)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", skills);
        result.put("unknown", unknown);
        return result;
    }

    /** Inside a run — that run's scope; outside one (an MCP client, the manage listing) — the agent's. */
    private RunCatalog.Catalog catalog(ConnectorEnv env) {
        if (env.agentId() == null) {
            throw new ConnectorException("load_skill requires an agent context");
        }
        return env.runId() != null
                ? runCatalog.forRun(env.agentId(), env.runId())
                : runCatalog.forAgent(env.agentId());
    }
}
