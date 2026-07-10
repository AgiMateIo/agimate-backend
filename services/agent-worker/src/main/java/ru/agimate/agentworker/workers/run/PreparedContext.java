package ru.agimate.agentworker.workers.run;

import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.agent.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * Agent context rendered once before the loop runs — the serializable result of the
 * {@code prepare_context} durable step. The tool registry is carried as its serializable parts
 * ({@code toolDefs} + {@code toolMap}) and reconstructed via {@link #registry()}.
 *
 * @param systemPrompt        rendered system prompt (ordered blocks with tags)
 * @param userPrompt          rendered persistent part of the user turn (what history keeps)
 * @param ephemeralUserSuffix rendered ephemeral user blocks (memory notes etc.), appended to the
 *                            model-facing turn but never persisted; {@code null} when none
 */
public record PreparedContext(
        String systemPrompt,
        String userPrompt,
        String ephemeralUserSuffix,
        List<ToolDef> toolDefs,
        Map<String, ToolRegistry.BackendTool> toolMap) {

    public ToolRegistry registry() {
        return ToolRegistry.of(toolDefs, toolMap);
    }
}
