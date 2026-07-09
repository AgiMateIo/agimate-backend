package ru.agimate.agentworker.workers.run;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.ConnectionRef;
import ru.agimate.agentworker.GetConnectionToolsResponse;
import ru.agimate.agentworker.GetConnectionsResponse;
import ru.agimate.agentworker.GetMemoryNotesResponse;
import ru.agimate.agentworker.GetSkillsResponse;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.TeamContext;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.context.ContextMaterials;
import ru.agimate.agentworker.agent.context.ContextProfile;
import ru.agimate.agentworker.agent.context.SystemPromptBuilder;
import ru.agimate.agentworker.dto.Trigger;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches the raw {@link ContextMaterials} for one run over gRPC, scoped by the
 * {@link ContextProfile}: dialogue keeps every skill in scope (no bodies); the trigger profile
 * narrows scope to the batch's matched skills and loads their bodies. Tools are fetched only for
 * connections whose connector is required by an in-scope skill. This is the wire→materials seam —
 * stage 2 collapses the call sequence into a single {@code GetRunContext} RPC without moving it.
 */
@Slf4j
public class ContextMaterialsFetcher {

    private final AgentWorkerClient client;

    public ContextMaterialsFetcher(AgentWorkerClient client) {
        this.client = client;
    }

    /** {@code batch} is the trigger batch for {@link ContextProfile#SYSTEM_TRIGGER}; null for dialogue. */
    public ContextMaterials fetch(String agentId, ContextProfile profile, List<Trigger> batch) {
        GetSkillsResponse skillsResp = client.getSkills(agentId);
        List<SkillRef> listed = skillsResp.getSkillsList();

        List<SkillSpec> loadedSkills = new ArrayList<>();
        List<SkillRef> scoped;
        if (profile.loadsSkillBodies()) {
            scoped = SystemPromptBuilder.selectBatchSkills(listed, batch);
            for (SkillRef s : scoped) {
                loadedSkills.add(client.getSkill(s.getSkillId()));
            }
        } else {
            scoped = listed;
        }
        Set<String> required = new LinkedHashSet<>();
        for (SkillRef s : scoped) {
            required.addAll(s.getConnectorCodesList());
        }

        AgentSpec spec = client.getAgentSpec(agentId);
        TeamContext teamCtx = spec.getTeamId().isBlank() ? null : client.getTeamContext(spec.getTeamId());
        AgentMemory memory = client.getMemory(agentId);
        GetMemoryNotesResponse notesResp = client.getMemoryNotes(agentId);
        log.debug("{} path; {} skill(s) listed, {} in scope",
                profile == ContextProfile.DIALOGUE ? "dialogue" : "trigger", listed.size(), scoped.size());

        GetConnectionsResponse connsResp = client.getConnections(agentId);
        List<ToolRegistry.ConnectorTools> loadedTools = new ArrayList<>();
        for (ConnectionRef conn : connsResp.getConnectionsList()) {
            if (!required.contains(conn.getConnectorCode())) {
                continue;
            }
            GetConnectionToolsResponse toolsResp = client.getConnectionTools(conn.getId());
            loadedTools.add(new ToolRegistry.ConnectorTools(conn.getConnectorCode(), toolsResp.getToolsList()));
        }

        return new ContextMaterials(spec, teamCtx, listed, loadedSkills, memory,
                notesResp.getNotesList(), loadedTools);
    }
}
