package ru.agimate.controlapi.database.enums;

/**
 * Вид сообщения в истории сессии (SaveMessage): «диалог как видел пользователь».
 */
public enum ChannelSessionMessageKind {
    INBOUND,
    PROGRESS,
    ANSWER,
    ERROR
}
