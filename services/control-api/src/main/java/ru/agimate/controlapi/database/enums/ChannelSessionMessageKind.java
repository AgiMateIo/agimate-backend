package ru.agimate.controlapi.database.enums;

/**
 * Вид сообщения в истории сессии. v2 (SaveMessage): INBOUND/PROGRESS/ANSWER/ERROR — «диалог как
 * видел пользователь». REQUEST/RESPONSE — дореформенные строки (полные LLM-ходы с message_json),
 * остаются только для чтения старой истории.
 */
public enum ChannelSessionMessageKind {
    REQUEST,
    RESPONSE,
    INBOUND,
    PROGRESS,
    ANSWER,
    ERROR
}
