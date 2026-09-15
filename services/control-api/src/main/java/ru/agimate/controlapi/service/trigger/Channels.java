package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * The channels of an agent's interaction within a single run. {@code prompt} is the inbound channel
 * (where the trigger came from); {@code progress} is filled with the same channel when its handler
 * delivers intermediate output ({@code ChannelHandler.deliverProgress}, webchat); {@code answer} is
 * set only on runs that answer somewhere other than an inbound — events carrying a conversation on
 * ({@link #continuation()}) and reminders; without it delivery falls back to {@code prompt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Channels(ChannelInfo prompt, ChannelInfo progress, ChannelInfo answer) {

    public static Channels ofPrompt(ChannelInfo prompt) {
        return new Channels(prompt, null, null);
    }

    /**
     * The channels of an event that carries this run's conversation on (a detached tool's result, a
     * subagent's report): no prompt — nothing came in — and the answer where this run's answer went,
     * its address included.
     *
     * @return {@code null} when this run delivered nowhere
     */
    public Channels continuation() {
        ChannelInfo effectiveAnswer = answer != null ? answer : prompt;
        if (effectiveAnswer == null && progress == null) {
            return null;
        }
        return new Channels(null, progress, effectiveAnswer);
    }

    /**
     * The channel session of this route: the prompt channel's, otherwise the answer channel's.
     * It is the marker of «a channel run» — the projection into {@code channel_session_messages},
     * the history and the stop command from a channel are all keyed on it, and until every run had
     * a session of its own this was exactly what {@code agent_runs.session_id} held.
     *
     * @return {@code null} when the run has no channel at all
     */
    public UUID sessionId() {
        if (prompt != null && prompt.sessionId() != null) {
            return prompt.sessionId();
        }
        return answer != null ? answer.sessionId() : null;
    }

    /** The same, for a {@code Channels} that may itself be absent (a direct run). */
    public static UUID sessionIdOf(Channels channels) {
        return channels == null ? null : channels.sessionId();
    }
}
