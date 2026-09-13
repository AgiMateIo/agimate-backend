package ru.agimate.controlapi.connectors.internal.toolloader;

import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.Disclosure;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.service.runcontext.RunTool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("load_tools")
class ToolLoaderToolServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    @Mock private RunCatalog runCatalog;

    private static final ConnectorEnv IN_RUN = new ConnectorEnv("conn-0", UUID.randomUUID(), AGENT_ID, RUN_ID, null, null, Map.of(), null);
    private static final ConnectorEnv NO_RUN = new ConnectorEnv("conn-0", UUID.randomUUID(), AGENT_ID, null, null, null, Map.of(), null);

    private ToolLoaderConnectorService connector;

    private static RunTool tool(String name, ru.agimate.controlapi.database.enums.Disclosure axis) {
        var spec = new ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec(name, null, "List agents. Details.",
                JsonSchema.object(Map.of("limit", JsonSchema.scalar("integer", "max")), List.of(), null),
                null, null, null, null);
        return new RunTool(spec, "platform", "conn-1", "platform", "platform__" + name, axis, null);
    }

    private static RunCatalog.Catalog catalog(RunTool... tools) {
        return new RunCatalog.Catalog(null, null, null, null, List.of(), List.of(), List.of(), List.of(tools), false, true);
    }

    @BeforeEach
    void setUp() {
        // Through the facade: it binds the env the tool reads and converts the names list like the real dispatch.
        connector = new ToolLoaderConnectorService(new ToolLoaderToolService(runCatalog));
    }

    private Map<String, Object> loadTools(ConnectorEnv env, List<String> names) {
        return connector.executeTool(env, "load_tools", Map.of("names", names));
    }

    @Test
    @DisplayName("the delta carries the EAGER proto-JSON spec of every known name and lists the misses; it parses back on the worker side")
    void deltaRoundTrips() throws Exception {
        when(runCatalog.forRun(AGENT_ID, RUN_ID)).thenReturn(catalog(
                tool("agent_list", ru.agimate.controlapi.database.enums.Disclosure.LAZY)));

        Map<String, Object> result = loadTools(IN_RUN, List.of("platform__agent_list", "platform__nope"));

        assertEquals(List.of("platform__nope"), result.get("unknown"));
        @SuppressWarnings("unchecked")
        Map<String, Object> printed = ((List<Map<String, Object>>) result.get("tools")).get(0);
        // Through the same serialisation the tool result takes to the worker, then the worker's parser.
        ConnectorToolSpec.Builder parsed = ConnectorToolSpec.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(JsonUtils.writeValueAsString(printed), parsed);
        ConnectorToolSpec spec = parsed.build();
        assertEquals("platform__agent_list", spec.getLlmName());
        assertEquals(Disclosure.DISCLOSURE_EAGER, spec.getDisclosure());
        assertTrue(spec.getInputSchema().toStringUtf8().contains("\"limit\""));
        assertEquals("conn-1", spec.getConnectionId());
    }

    @Test
    @DisplayName("nothing known: an error naming the deferred tools of the namespace the model tried")
    void allUnknown() {
        when(runCatalog.forRun(AGENT_ID, RUN_ID)).thenReturn(catalog(
                tool("agent_list", ru.agimate.controlapi.database.enums.Disclosure.LAZY),
                tool("agent_get", ru.agimate.controlapi.database.enums.Disclosure.EAGER)));

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> loadTools(IN_RUN, List.of("platform__agents")));

        assertTrue(e.getMessage().contains("platform__agent_list"));
        assertTrue(!e.getMessage().contains("platform__agent_get"), "eager tools need no loading");
    }

    @Test
    @DisplayName("outside a run the scope is the agent's")
    void agentScopeWithoutRun() {
        when(runCatalog.forAgent(AGENT_ID)).thenReturn(catalog(
                tool("agent_list", ru.agimate.controlapi.database.enums.Disclosure.LAZY)));

        loadTools(NO_RUN, List.of("platform__agent_list"));

        verify(runCatalog).forAgent(AGENT_ID);
    }
}
