package ru.agimate.controlapi.database.enums;

/**
 * Who pays for the agent's models: the platform or the user's own keys. The source, not the presence
 * of bindings, decides the chain — a PLATFORM agent keeps its bindings inactive while the platform
 * serves it (docs/decisions/platform-llm-purposes-and-agent-source.md).
 */
public enum LlmSource {
    PLATFORM,
    BYOK
}
