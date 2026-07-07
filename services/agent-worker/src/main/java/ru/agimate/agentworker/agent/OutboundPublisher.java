package ru.agimate.agentworker.agent;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.dto.ChannelInfo;
import ru.agimate.agentworker.dto.Channels;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Routes an agent's output back to the run's channels by role, wrapping text and pushing it via
 * {@code ChannelGateway.SendChannelMessage}. Each role resolves to a channel with a fallback chain;
 * an absent channel just logs. Created per run (not a Spring bean).
 *
 * <ul>
 *   <li>{@code progress} → {@code channels.progress} (else log)</li>
 *   <li>{@code answer}   → {@code channels.answer or channels.prompt} (else log)</li>
 *   <li>{@code error}    → {@code channels.progress or channels.answer or channels.prompt} (else log)</li>
 * </ul>
 *
 * <p>Each message carries a deterministic {@code message_id} derived from {@code (run_id, stream,
 * sequence)} so a DBOS replay re-sends the same ids and control-api dedupes instead of
 * double-posting. The three streams keep independent sequence counters so ids never collide even
 * when two roles resolve to the same channel.
 */
@Slf4j
public class OutboundPublisher {

    private final AgentWorkerClient client;
    private final String agentId;
    private final String runId;
    private final ChannelInfo progressCh;
    private final ChannelInfo answerCh;
    private final ChannelInfo errorCh;
    private final Map<String, Integer> seq = new HashMap<>(Map.of("progress", 0, "answer", 0, "error", 0));

    public OutboundPublisher(AgentWorkerClient client, String agentId, Channels channels, String runId) {
        this.client = client;
        this.agentId = agentId;
        this.runId = runId;
        ChannelInfo prompt = channels != null ? channels.prompt() : null;
        ChannelInfo progress = channels != null ? channels.progress() : null;
        ChannelInfo answer = channels != null ? channels.answer() : null;
        this.progressCh = progress;
        this.answerCh = answer != null ? answer : prompt;
        this.errorCh = progress != null ? progress : (answer != null ? answer : prompt);
    }

    public void progress(String text) {
        send("progress", progressCh, text);
    }

    public void answer(String text) {
        send("answer", answerCh, text);
    }

    public void error(String text) {
        send("error", errorCh, text);
    }

    private void send(String stream, ChannelInfo channel, String text) {
        if (channel == null) {
            log.info("agent output [{}]: {}", stream, text);
            return;
        }
        int n = seq.get(stream);
        String messageId = deterministicId(runId, stream, n);
        seq.put(stream, n + 1);
        var reply = client.sendChannelMessage(
                agentId, channel.channelId(), channel.sessionId() != null ? channel.sessionId() : "",
                messageId, text);
        log.info("SendChannelMessage acked [{}]: session_id={} message_id={}",
                stream, reply.getSessionId(), reply.getMessageId());
    }

    /** Deterministic UUID (v3/MD5) from a stable name so replays produce identical ids. */
    private static String deterministicId(String runId, String stream, int seq) {
        String name = "agimate-outbound:" + runId + ":" + stream + ":" + seq;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
