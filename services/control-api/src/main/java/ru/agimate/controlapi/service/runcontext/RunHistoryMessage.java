package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;

/**
 * Сообщение истории «как видел пользователь» в составе контекста рана. Дореформенные kinds
 * уже смаплены на v2 (REQUEST → INBOUND, RESPONSE → ANSWER) — воркер знает только v2-виды.
 */
public record RunHistoryMessage(ChannelSessionMessageKind kind, String text) {
}
