package ru.agimate.controlapi.grpc.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.Disclosure;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.service.runcontext.RunTool;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RunContextMapper: tools")
class RunContextMapperTest {

    private static RunTool tool() {
        var spec = new ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec(
                "fetch", null, "Fetch a page. Long tail.",
                JsonSchema.object(Map.of("url", JsonSchema.scalar("string", null)), List.of("url"), null),
                null, new ToolAnnotationsSpec(true, false, true, true),
                Map.of("agimate.context_material", "tools"), 30);
        return new RunTool(spec, "mcp", "conn-1", "mcp_web", "mcp_web__fetch",
                ru.agimate.controlapi.database.enums.Disclosure.EAGER, null);
    }

    @Test
    @DisplayName("EAGER: the full spec, disclosure EAGER, llm_name from the backend")
    void eager() {
        ConnectorToolSpec proto = RunContextMapper.toProto(tool());

        assertEquals(Disclosure.DISCLOSURE_EAGER, proto.getDisclosure());
        assertEquals("mcp_web__fetch", proto.getLlmName());
        assertTrue(proto.getInputSchema().toStringUtf8().contains("\"url\""));
        assertEquals("", proto.getSummary());
    }

    @Test
    @DisplayName("LAZY: no schema and a summary, but routing, annotations and _meta stay — the worker needs them")
    void lazyKeepsEverythingButTheSchema() {
        ConnectorToolSpec proto = RunContextMapper.toProto(tool().lazy("Fetch a page."));

        assertEquals(Disclosure.DISCLOSURE_LAZY, proto.getDisclosure());
        assertTrue(proto.getInputSchema().isEmpty());
        assertEquals("Fetch a page.", proto.getSummary());
        assertEquals("mcp_web__fetch", proto.getLlmName());
        assertEquals("mcp", proto.getConnectorCode());
        assertEquals("conn-1", proto.getConnectionId());
        assertEquals("mcp_web", proto.getNamespace());
        assertTrue(proto.getAnnotations().getOpenWorldHint());
        assertEquals("tools", proto.getMetaMap().get("agimate.context_material"));
        assertEquals(30, proto.getTimeoutSeconds());
        assertEquals("Fetch a page. Long tail.", proto.getDescription());
    }
}
