package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

/**
 * A history message «as the user saw it», within a run's context.
 *
 * <p>{@code toolTurn} (protocol v2.1) is the structural record of a tool turn on PROGRESS/TOOL_CALL:
 * from it the worker restores native tool_use/tool_result instead of the textual 🔧 projection (text
 * in history is something the model imitates instead of making a real call). {@code null} — an
 * ordinary text line.
 */
public record RunHistoryMessage(ChannelSessionMessageKind kind, String text, ToolTurnRecord toolTurn) {

    public RunHistoryMessage(ChannelSessionMessageKind kind, String text) {
        this(kind, text, null);
    }
}
