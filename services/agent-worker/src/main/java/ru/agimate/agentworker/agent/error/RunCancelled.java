package ru.agimate.agentworker.agent.error;

import java.util.List;

/**
 * The user stopped the run. Not a failure: the loop reached a seam, saw the request and left off
 * deliberately — so this travels separately from {@link AgentRunAborted} and ends the run with an
 * ordinary answer rather than an error notice.
 *
 * <p>It carries the tools that ran to a successful result before the stop. Only the loop knows that,
 * and the user is owed it: an interrupted agent that already sent a message must say so.
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
