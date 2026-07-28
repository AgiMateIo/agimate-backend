package ru.agimate.controlapi.database.enums;

/** Role of a turn in the canonical journal {@code agent_run_turns} (mirrors the worker's {@code AgentChatMessage.Role}). */
public enum AgentTurnRole {
    SYSTEM, USER, ASSISTANT, TOOL
}
