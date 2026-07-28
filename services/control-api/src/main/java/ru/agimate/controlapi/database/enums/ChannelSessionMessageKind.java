package ru.agimate.controlapi.database.enums;

/**
 * Kind of a message in session history (SaveMessage): «the dialogue as the user saw it».
 */
public enum ChannelSessionMessageKind {
    INBOUND,
    PROGRESS,
    ANSWER,
    ERROR
}
