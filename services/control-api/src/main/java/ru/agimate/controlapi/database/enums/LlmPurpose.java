package ru.agimate.controlapi.database.enums;

/**
 * Purpose of an agent's LLM binding ({@code agent_llms.purpose}): what role the model plays for the
 * agent. {@link #CHAT} is the main model of the agent loop (the one {@code GetLlmCredentials}
 * returns); IMAGE/VISION/AUDIO_* are tool models of the media connector, resolved by purpose with a
 * fallback to a capability match against the registry ({@code input/output_modalities}).
 */
public enum LlmPurpose {

    /** The main chat model of the agent loop. */
    CHAT,

    /** Image generation and editing ({@code output_modalities ⊇ ["image"]}). */
    IMAGE,

    /** Vision: describing an image from a file ({@code input_modalities ⊇ ["image"]}). */
    VISION,

    /** Speech and voice recognition ({@code input_modalities ⊇ ["audio"]}). */
    AUDIO_IN,

    /** Speech synthesis ({@code output_modalities ⊇ ["audio"]}). */
    AUDIO_OUT,

    /**
     * The platform's own chores over the agent's data — compacting a long conversation, titling a
     * session. Falls back to the agent's CHAT model rather than failing: the chore is the platform's
     * idea, and it must not stop working because nobody configured a model for it.
     */
    ROUTINE
}
