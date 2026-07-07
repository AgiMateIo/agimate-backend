package ru.agimate.agentworker.workers;

import io.grpc.Status;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.dto.ChannelInfo;
import ru.agimate.agentworker.dto.Channels;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;

/** Shared session-resolution and active-run claim helpers for the router and the run stage. */
final class SessionSupport {

    private SessionSupport() {
    }

    /**
     * The session that keys single-writer/history. The rule lives on the producer: control-api
     * resolves it once and ships {@code AgentMessage.sessionId}. The channel-derived fallback
     * (prompt's, else answer's) only covers messages enqueued before the field existed.
     */
    static String sessionId(AgentMessage message) {
        if (message.sessionId() != null && !message.sessionId().isEmpty()) {
            return message.sessionId();
        }
        Channels channels = message.channels();
        if (channels == null) {
            return null;
        }
        ChannelInfo channel = channels.prompt() != null ? channels.prompt() : channels.answer();
        return (channel != null && channel.sessionId() != null && !channel.sessionId().isEmpty())
                ? channel.sessionId() : null;
    }

    /**
     * Atomic claim of the session's active-run slot. Returns {@code true} when acquired (or an
     * idempotent re-affirm by the same run), {@code false} when another run holds it (gRPC ABORTED).
     * Other statuses propagate.
     */
    static boolean tryRegister(AgentWorkerClient client, String agentId, String session, String runId, int ttl) {
        try {
            client.registerRun(agentId, session, runId, ttl);
            return true;
        } catch (ControlApiCallException e) {
            if (e.code() == Status.Code.ABORTED) {
                return false;
            }
            throw e;
        }
    }
}
