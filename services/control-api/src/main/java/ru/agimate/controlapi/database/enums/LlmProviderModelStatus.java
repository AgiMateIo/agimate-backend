package ru.agimate.controlapi.database.enums;

/**
 * Status of a model in the {@code llm_provider_models} registry — freshness according to the last
 * successful discovery listing of the provider, NOT runtime truth (listings are sometimes
 * incomplete, and some OpenAI-compatible backends do not implement {@code /models} at all).
 * Advisory: shown in the UI (a badge on the agent's binding), but LLM calls are not blocked by it —
 * authority on «the model is alive» stays with the provider at call time.
 */
public enum LlmProviderModelStatus {

    /** The model was present in the provider's last successful listing. */
    AVAILABLE,

    /** The model disappeared from the listing (or never appeared in one — {@code first_seen_at} null). */
    UNAVAILABLE
}
