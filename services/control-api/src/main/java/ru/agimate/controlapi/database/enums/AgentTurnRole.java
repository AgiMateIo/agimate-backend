package ru.agimate.controlapi.database.enums;

/** Роль хода в каноническом журнале {@code agent_run_turns} (зеркало {@code AgentChatMessage.Role} воркера). */
public enum AgentTurnRole {
    SYSTEM, USER, ASSISTANT, TOOL
}
