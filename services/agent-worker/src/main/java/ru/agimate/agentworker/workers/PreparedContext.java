package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.agent.ToolDef;
import ru.agimate.agentworker.agent.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * Agent context fetched and derived once before the loop runs — the serializable result of the
 * {@code prepare_context} durable step. The tool registry is carried as its serializable parts
 * ({@code toolDefs} + {@code toolMap}) and reconstructed via {@link #registry()}; {@code memoryNotes}
 * is null when there are none.
 */
public record PreparedContext(
        String systemPrompt,
        String memoryNotes,
        List<ToolDef> toolDefs,
        Map<String, ToolRegistry.BackendTool> toolMap) {

    public ToolRegistry registry() {
        return ToolRegistry.of(toolDefs, toolMap);
    }
}
