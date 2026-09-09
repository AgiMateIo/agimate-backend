package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSizeReportTest {

    private static RunTool tool(String namespace, String description, JsonSchema schema) {
        return new RunTool(new ConnectorToolSpec("t", null, description, schema, null, null, null, null),
                namespace, "c", namespace, namespace + "__t", Disclosure.EAGER, null);
    }

    /** A deferred tool: the same spec, but only its listing line travels. */
    private static RunTool deferred(String namespace, String summary) {
        return new RunTool(new ConnectorToolSpec("t", null, "a long description that stays on the backend",
                JsonSchema.object(Map.of(), List.of(), null), null, null, null, null),
                namespace, "c", namespace, namespace + "__t", Disclosure.LAZY, summary);
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
                + " tools=2 (2 eager: desc 5 B, schema " + schema.length() + " B)"
                + " {platform=1:4/" + schema.length() + "B mcp_ctx=1:1/0B }"
                + " history=2 msg 5 B", report);
    }

    @Test
    @DisplayName("отложенный тул считается строкой листинга, а не описанием со схемой")
    void deferredCountsAsItsListingLine() {
        RunContextView view = new RunContextView(List.of(), List.of(),
                List.of(tool("platform", "desc", null), deferred("sheets", "Read rows.")),
                List.of(), List.of());

        String report = ContextSizeReport.of(view);

        // sheets__t = 9 bytes + "Read rows." = 10 → 19, and neither the description nor the schema counts.
        assertTrue(report.contains("tools=2 (1 eager: desc 4 B, schema 0 B; 1 deferred: 19 B)"), report);
        assertTrue(report.contains("sheets=1:0/0/19dB"), report);
    }
}
