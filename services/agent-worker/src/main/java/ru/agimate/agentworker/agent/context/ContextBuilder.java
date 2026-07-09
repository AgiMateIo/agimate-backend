package ru.agimate.agentworker.agent.context;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.workers.run.PreparedContext;

/**
 * The context-assembly seam: composes fetched {@link ContextMaterials} into a
 * {@link PreparedContext} according to a {@link ContextProfile} — the single place that reads
 * "how is the context built for this kind of input" (system prompt ± trigger guidance, tool
 * registry, memory notes). Pure: no gRPC, no DBOS.
 *
 * <p>{@code PreparedContext} stays in {@code workers.run} — its FQCN is pinned by the DBOS
 * {@code prepare_context} checkpoint (in-flight runs replay the serialized step result across
 * deploys), so this pure package deliberately references it there.
 */
@Slf4j
public final class ContextBuilder {

    private ContextBuilder() {
    }

    public static PreparedContext build(ContextProfile profile, ContextMaterials materials) {
        String systemPrompt = SystemPromptBuilder.build(materials.spec(), materials.teamCtx(),
                materials.skills(), materials.loadedSkills(), materials.memory());
        if (profile.appendsTriggerGuidance()) {
            systemPrompt = systemPrompt + "\n\n" + SystemPromptBuilder.TRIGGER_GUIDANCE;
        }

        ToolRegistry registry = ToolRegistry.build(materials.connectorTools());
        log.info("context ready [{}]: {} tool(s)",
                profile == ContextProfile.DIALOGUE ? "dialogue" : "trigger", registry.toolDefs().size());
        log.debug("tools: {}", registry.names());

        String memoryNotes = RequestBuilder.renderMemoryNotes(materials.notes());
        return new PreparedContext(systemPrompt, memoryNotes, registry.toolDefs(), registry.backendMap());
    }
}
