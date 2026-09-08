package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextSizeReportTest {

    private static RunTool tool(String namespace, String description, JsonSchema schema) {
        return new RunTool(new ConnectorToolSpec("t", null, description, schema, null, null, null, null),
                namespace, "c", namespace, namespace + "__t", ru.agimate.controlapi.database.enums.Disclosure.EAGER, null);
    }

    @Test
    @DisplayName("counts UTF-8 bytes per block name, per tool instance and across history")
    void report() {
        RunContextView view = new RunContextView(
                List.of(RunBlock.trusted("agent", "agent", "ab", Map.of()),
                        RunBlock.trusted("skill", "skill", "тело", Map.of()),
                        RunBlock.trusted("skill", "skill", "x", Map.of())),
                List.of(RunBlock.trusted("", "user", "hi", Map.of())),
                List.of(tool("platform", "desc", JsonSchema.object(Map.of(), List.of(), null)),
                        tool("mcp_ctx", "d", null)),
                List.of(new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "hey"),
                        new RunHistoryMessage(ChannelSessionMessageKind.PROGRESS, "",
                                new ToolTurnRecord(null, List.of(new ToolTurnRecord.Call("1", "t", "{}")), List.of()))),
                List.of());

        String report = ContextSizeReport.of(view);

        String schema = "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        assertEquals("system=11 B {agent=2, skill=9} user=2 B {user=2}"
                + " tools=2 (desc 5 B, schema " + schema.length() + " B)"
                + " {platform=1:4/" + schema.length() + "B mcp_ctx=1:1/0B }"
                + " history=2 msg 5 B", report);
    }
}
