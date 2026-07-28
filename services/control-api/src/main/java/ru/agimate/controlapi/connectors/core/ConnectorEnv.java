package ru.agimate.controlapi.connectors.core;

import java.util.Map;
import java.util.UUID;

/**
 * Environment of a single call into a connector's SPI (tool, job, prompt blocks, listing, webhook):
 * instance addressing, the caller's identity and secrets.
 *
 * @param connectionId  identifier of the connector instance — {@code connections.id} as a string (as
 *                      in {@code ToolCallLog}); {@code null} when no instance applies
 * @param userId        the owner; {@code null} for global internal jobs
 * @param agentId       the initiating agent; {@code null} outside a tool-use flow (declarative jobs,
 *                      webhooks); for a dynamic job it is restored from the row when the job fires
 * @param runId         the run that initiated the call ({@code agent_runs.id}); {@code null} outside a
 *                      run's tool-use flow (webhooks, listing, jobs, lifecycle). Needed by the
 *                      «model as a tool» usage accounting (media) to attribute usage to a run
 * @param channelId     the call's originating channel: for a tool call it is the prompt session's
 *                      channel (resolved at the boundary from {@code agentSessionId}); for a dynamic
 *                      job it is the snapshot taken from the {@code connector_jobs} row. {@code null}
 *                      outside a channel context. Needed by tools that care about the originating
 *                      channel (e.g. {@code time.schedule} — where to answer)
 * @param sessionId     the call's prompt session ({@code channel_sessions.id}); {@code null} outside a
 *                      channel tool-use flow. Needed by tools addressing one particular live session
 *                      (the IDE connector — the key of {@code AcpSessionRegistry})
 * @param credentials   decrypted credentials; an empty map for internal connectors and for the
 *                      webhook hot path (validation and normalisation need no decryption)
 * @param webhookSecret secret for validating incoming webhooks; {@code null} when not applicable
 */
public record ConnectorEnv(
        String connectionId,
        UUID userId,
        UUID agentId,
        UUID runId,
        UUID channelId,
        UUID sessionId,
        Map<String, String> credentials,
        String webhookSecret
) {

    public ConnectorEnv {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
}
