package ru.agimate.agentworker.agent;

/**
 * Clean exit from the agent loop on an INTERRUPT steering signal. The run catches it, skips the
 * final answer emit, and releases its session slot in {@code finally} — a graceful stop (no hard
 * workflow cancel), so in-flight LLM/tool steps finish and history ends on a coherent turn.
 */
public class AgentInterrupted extends RuntimeException {
    public AgentInterrupted() {
        super("agent run interrupted by a steering signal");
    }
}
