package ru.agimate.agentworker.agent.error;

/**
 * Terminal but expected end of an agent run. Raised from anywhere in the run body to unwind to a
 * single handler that reports {@code userNotice} to the channel and {@code systemDetail} to the
 * backend, after which the workflow returns normally — so DBOS records a success, not a failure,
 * and does not attempt recovery. Genuine infra errors (gRPC/DB) are NOT wrapped in this; they
 * propagate and mark the workflow ERROR — terminal, DBOS recovery replays only PENDING workflows —
 * after a best-effort infra-failure notice from the run workflow.
 */
public class AgentRunAborted extends RuntimeException {
    private final String userNotice;
    private final String systemDetail;

    public AgentRunAborted(String userNotice, String systemDetail) {
        super(systemDetail);
        this.userNotice = userNotice;
        this.systemDetail = systemDetail;
    }

    public String userNotice() {
        return userNotice;
    }

    public String systemDetail() {
        return systemDetail;
    }
}
