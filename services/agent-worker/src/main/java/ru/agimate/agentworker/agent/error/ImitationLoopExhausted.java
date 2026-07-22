package ru.agimate.agentworker.agent.error;

/**
 * The model kept imitating a tool call as text (see {@code SimpleAgent.TOOL_TEXT_IMITATION}) after
 * the loop spent all its correction nudges. A degenerate finish, not a real answer — raised instead
 * of surfacing the raw imitation string to the user.
 */
public class ImitationLoopExhausted extends RuntimeException {
    public ImitationLoopExhausted(String message) {
        super(message);
    }
}
