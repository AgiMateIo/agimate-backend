package ru.agimate.agentworker.agent;

/** The agent loop hit {@code maxTurns} without producing a final text reply. */
public class MaxTurnsExceeded extends RuntimeException {
    public MaxTurnsExceeded(String message) {
        super(message);
    }
}
