package ru.agimate.agentworker.workers.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.ConnectionRef;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.GetConnectionToolsResponse;
import ru.agimate.agentworker.GetConnectionsResponse;
import ru.agimate.agentworker.GetMemoryNotesResponse;
import ru.agimate.agentworker.GetSkillsResponse;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.agent.context.ContextMaterials;
import ru.agimate.agentworker.agent.context.ContextProfile;
import ru.agimate.agentworker.dto.Trigger;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextMaterialsFetcherTest {

    private static final String AGENT_ID = "a-1";

    private AgentWorkerClient client;
    private ContextMaterialsFetcher fetcher;

    private static SkillRef skill(String id, String... codes) {
        return SkillRef.newBuilder().setSkillId(id).setName(id).addAllConnectorCodes(List.of(codes)).build();
    }

    private static ConnectionRef connection(String id, String connectorCode) {
        return ConnectionRef.newBuilder().setId(id).setConnectorCode(connectorCode).build();
    }

    private static GetConnectionToolsResponse tools(String name, String connectionId) {
        return GetConnectionToolsResponse.newBuilder()
                .addTools(ConnectorToolSpec.newBuilder()
                        .setName(name).setConnectionId(connectionId).setDescription("d").build())
                .build();
    }

    @BeforeEach
    void setUp() {
        client = mock(AgentWorkerClient.class);
        fetcher = new ContextMaterialsFetcher(client);

        when(client.getSkills(AGENT_ID)).thenReturn(GetSkillsResponse.newBuilder()
                .addSkills(skill("s1", "board"))
                .addSkills(skill("s2", "time"))
                .build());
        when(client.getAgentSpec(AGENT_ID)).thenReturn(AgentSpec.newBuilder().setAgentId(AGENT_ID).build());
        when(client.getMemory(AGENT_ID)).thenReturn(AgentMemory.getDefaultInstance());
        when(client.getMemoryNotes(AGENT_ID)).thenReturn(GetMemoryNotesResponse.getDefaultInstance());
        when(client.getConnections(AGENT_ID)).thenReturn(GetConnectionsResponse.newBuilder()
                .addConnections(connection("c-board", "board"))
                .addConnections(connection("c-slack", "slack"))
                .build());
        when(client.getConnectionTools("c-board")).thenReturn(tools("get_tasks", "c-board"));
    }

    @Test
    @DisplayName("DIALOGUE: all skills listed without bodies; tools only for connectors the skills declare")
    void dialogue() {
        ContextMaterials materials = fetcher.fetch(AGENT_ID, ContextProfile.DIALOGUE, null);

        assertEquals(2, materials.skills().size());
        assertTrue(materials.loadedSkills().isEmpty());
        verify(client, never()).getSkill(anyString());
        // slack is not declared by any skill → its connection tools are never fetched
        assertEquals(1, materials.connectorTools().size());
        assertEquals("board", materials.connectorTools().get(0).connectorCode());
        verify(client, never()).getConnectionTools("c-slack");
        // no team on the spec → team context not fetched
        assertNull(materials.teamCtx());
        verify(client, never()).getTeamContext(anyString());
    }

    @Test
    @DisplayName("SYSTEM_TRIGGER: only matched skills loaded; toolset scoped to their connectors")
    void systemTrigger() {
        when(client.getSkill("s1")).thenReturn(SkillSpec.newBuilder().setSkillId("s1").setSkillMd("body").build());
        Trigger trigger = new Trigger("board", "ident", "task.created", "t-1", Map.of(), "2026-07-09T00:00:00Z");

        ContextMaterials materials = fetcher.fetch(AGENT_ID, ContextProfile.SYSTEM_TRIGGER, List.of(trigger));

        // full listing is preserved for the "## Skills" section; bodies only for the matched skill
        assertEquals(2, materials.skills().size());
        assertEquals(List.of("s1"), materials.loadedSkills().stream().map(SkillSpec::getSkillId).toList());
        verify(client, never()).getSkill("s2");
        assertEquals(1, materials.connectorTools().size());
        assertEquals("board", materials.connectorTools().get(0).connectorCode());
        verify(client, never()).getConnectionTools("c-slack");
    }
}
