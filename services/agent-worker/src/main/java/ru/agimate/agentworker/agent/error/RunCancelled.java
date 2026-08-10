package ru.agimate.agentworker.agent.error;

import java.util.List;

/**
 * The user stopped the run — not a failure, so it travels apart from {@link AgentRunAborted} and ends
 * the run with an ordinary answer. Carries the tools that succeeded before the stop: an interrupted
 * agent that already sent a message owes the user that much.
 */
public class RunCancelled extends RuntimeException {

    private final transient List<String> executedTools;

    public RunCancelled(List<String> executedTools) {
        super("run cancelled by the user");
        this.executedTools = List.copyOf(executedTools);
    }

    public List<String> executedTools() {
        return executedTools;
    }
}
