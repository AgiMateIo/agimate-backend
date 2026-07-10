package ru.agimate.agentworker.workers.run;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.RunContext;
import ru.agimate.agentworker.agent.context.ContextMaterials;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

/**
 * The wire→materials seam: one {@code GetRunContext} call per run. The backend assembles the
 * context (block scoping, ordering, trust flags, tool ABAC) — the worker receives ready blocks
 * and only renders them ({@code ContextBuilder}).
 */
@Slf4j
public class ContextMaterialsFetcher {

    private final AgentWorkerClient client;

    public ContextMaterialsFetcher(AgentWorkerClient client) {
        this.client = client;
    }

    public ContextMaterials fetch(String agentId, String triggerId) {
        RunContext context = client.getRunContext(agentId, triggerId);
        log.debug("run context fetched: {} system / {} user block(s), {} tool(s)",
                context.getSystemBlocksCount(), context.getUserBlocksCount(), context.getToolsCount());
        return new ContextMaterials(
                context.getSystemBlocksList(), context.getUserBlocksList(),
                context.getToolsList(), context.getHistoryList());
    }
}
