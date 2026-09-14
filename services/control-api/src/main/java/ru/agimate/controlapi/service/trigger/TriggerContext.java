package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * The producer's directives on a trigger (1:1 with the event): what has been attached for routing and
 * for the agent's context.
 * <p>
 * {@code audience} narrows the agent list after the policy (actor/targets). {@code channels} is the
 * declared form of the channels (a given prompt channel and so on). {@code sessionId} in the prompt
 * {@link ChannelInfo} is usually empty (it is per agent and is resolved in {@code TriggerRoute}), but
 * a producer that knows its channel's session (webchat: the frontend picks it explicitly) may set it —
 * {@code ChannelRouteResolver} then uses the open declared session instead of the TTL heuristic. Both
 * fields are optional: for an ordinary incoming webhook {@code context == null}.
 *
 * <p>{@code originRunId} — the run whose action raised this trigger (a subagent's request carries the
 * conversation run that asked); it lands in {@code agent_runs.origin_run_id} of every run routed from it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerContext(TriggerAudience audience, Channels channels, UUID originRunId) {

    public TriggerContext(TriggerAudience audience, Channels channels) {
        this(audience, channels, null);
    }

    public static TriggerContext audience(TriggerAudience audience) {
        return new TriggerContext(audience, null);
    }
}
