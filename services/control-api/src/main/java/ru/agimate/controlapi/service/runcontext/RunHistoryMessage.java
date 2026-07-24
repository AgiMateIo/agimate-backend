package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

/**
 * Сообщение истории «как видел пользователь» в составе контекста рана.
 *
 * <p>{@code toolTurn} (протокол v2.1) — структурная запись tool-хода у PROGRESS/TOOL_CALL:
 * воркер восстановит из неё нативные tool_use/tool_result вместо текстовой 🔧-проекции
 * (текст в истории модель имитирует вместо реального вызова). {@code null} — обычная
 * текстовая строка.
 */
public record RunHistoryMessage(ChannelSessionMessageKind kind, String text, ToolTurnRecord toolTurn) {

    public RunHistoryMessage(ChannelSessionMessageKind kind, String text) {
        this(kind, text, null);
    }
}
