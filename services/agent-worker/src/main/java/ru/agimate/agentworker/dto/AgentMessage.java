package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The DBOS workflow argument enqueued by control-api ({@code AgentMessage<Trigger>}). The
 * presence of {@code channels.prompt} — not the always-{@code "trigger"} {@code type} field —
 * decides how the message is handled: present → a user message on a channel ({@code inbound.text}),
 * absent → an autonomous trigger (the {@code payload} event as untrusted data). {@code channels}
 * and {@code inbound} are absent for direct, non-channel triggers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentMessage(
        String agentId,
        String runId,
        String type,
        Channels channels,
        InboundMessage inbound,
        Trigger payload
) {
    /** The input channel a user message arrived on, or {@code null} for a direct trigger. */
    public ChannelInfo promptChannel() {
        return channels != null ? channels.prompt() : null;
    }
}
