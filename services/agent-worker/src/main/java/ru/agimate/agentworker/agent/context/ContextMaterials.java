package ru.agimate.agentworker.agent.context;

import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.MemoryNote;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.TeamContext;
import ru.agimate.agentworker.agent.ToolRegistry;

import java.util.List;

/**
 * Raw, already-scoped materials for one context assembly — everything {@link ContextBuilder}
 * needs, nothing about how to get it (the workers-side fetcher produces it from gRPC; stage 2
 * will source it from one {@code GetRunContext} call without touching this seam).
 *
 * @param spec           the agent's own spec
 * @param teamCtx        team context, {@code null} when the agent has no team
 * @param skills         full skill listing (metadata only — feeds the "## Skills" section)
 * @param loadedSkills   bodies of the in-scope skills (trigger path; empty for dialogue)
 * @param memory         consolidated agent memory
 * @param notes          hot memory notes (not yet consolidated)
 * @param connectorTools tool specs per connector, already scoped to the profile
 */
public record ContextMaterials(
        AgentSpec spec,
        TeamContext teamCtx,
        List<SkillRef> skills,
        List<SkillSpec> loadedSkills,
        AgentMemory memory,
        List<MemoryNote> notes,
        List<ToolRegistry.ConnectorTools> connectorTools) {
}
