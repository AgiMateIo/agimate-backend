package ru.agimate.agentworker.agent.error;

/**
 * The model returned a tool-less turn with no text at all, and the loop spent its retry nudges. Seen
 * with reasoning models behind OpenAI-compatible gateways: the whole generation lands in
 * {@code reasoning_content} while {@code content} stays empty and {@code finish_reason} is still
 * {@code stop} — so nothing marks it as a failure. Raised instead of returning the empty string,
 * which would end the run «successfully» with the user staring at silence.
 */
public class EmptyAnswerExhausted extends RuntimeException {
    public EmptyAnswerExhausted(String message) {
        super(message);
    }
}
