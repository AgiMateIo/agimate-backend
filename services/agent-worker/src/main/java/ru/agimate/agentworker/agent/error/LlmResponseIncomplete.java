package ru.agimate.agentworker.agent.error;

/**
 * The model returned an unusable response: the provider's {@code finish_reason} says the output was
 * cut off ({@code length} — truncated by the token limit) or blocked ({@code content_filter}).
 * Raised by the LLM dispatcher; {@link AgentRunner} maps it to a per-reason user notice. Not
 * retryable — the same prompt reproduces it.
 */
public class LlmResponseIncomplete extends RuntimeException {

    public enum Reason {LENGTH, CONTENT_FILTER}

    private final Reason reason;

    public LlmResponseIncomplete(Reason reason) {
        super("llm response incomplete: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
