package ru.agimate.agentworker.workers.run;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.SteeringMessage;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The run's steering client: claims the session's queued messages at the loop seam
 * ({@code ClaimSteering}), records each into the turn ledger, and confirms the absorption
 * ({@code MarkSteered}) once the model has actually seen it. Both calls are best-effort and never
 * durable steps; every failure degrades toward the claimed run executing itself.
 *
 * <p>The backend re-fetches a claimed-but-unconfirmed run on purpose — that is what lets a crash
 * replay re-absorb what the model never saw. Within one live process the same re-fetch would be a
 * duplicate (the message is already in the conversation, only its confirmation is pending), so
 * {@link #absorbed} keeps the ids this process has taken and {@link #poll} skips them.
 */
@Slf4j
class SteeringAbsorber {

    private final AgentWorkerClient client;
    private final TurnLog turns;
    private final String agentId;
    private final String runId;
    /** How an absorbed message is presented to the model: arrived mid-run, not part of the original request. */
    private final String steeredPrefix;
    /** Absorbed by this process — a re-fetched unconfirmed claim must not enter the conversation twice. */
    private final Set<String> absorbed = new LinkedHashSet<>();
    /** Awaiting {@code MarkSteered}; retried on every assistant turn until the backend accepts. */
    private final List<String> unconfirmed = new ArrayList<>();

    SteeringAbsorber(AgentWorkerClient client, TurnLog turns, String agentId, String runId,
                     String steeredPrefix) {
        this.client = client;
        this.turns = turns;
        this.agentId = agentId;
        this.runId = runId;
        this.steeredPrefix = steeredPrefix;
    }

    /**
     * Claim and absorb whatever is queued: the messages ready to append to the conversation,
     * framed, oldest first. The ledger records the bare text — the framing is ephemeral, like the
     * prefix of the initial request: today's presentation must not settle into history.
     */
    List<AgentChatMessage> poll() {
        List<SteeringMessage> claimed;
        try {
            claimed = client.claimSteering(agentId, runId).getMessagesList();
        } catch (Exception e) {
            // Best-effort by design: the claimed run (if any) will simply execute itself.
            log.warn("steering claim failed (best-effort): {}", e.getMessage());
            return List.of();
        }
        List<AgentChatMessage> result = new ArrayList<>(claimed.size());
        for (SteeringMessage m : claimed) {
            if (!absorbed.add(m.getRunId())) {
                // A re-fetched unconfirmed claim: already in the conversation, confirmation pending.
                continue;
            }
            AgentChatMessage bare = AgentChatMessage.user(
                    m.getText(), ContextBuilder.mapParts(m.getPartsList()));
            turns.record(bare, null);
            result.add(withFraming(bare));
            unconfirmed.add(m.getRunId());
        }
        return result;
    }

    /**
     * Confirmation is hooked to the assistant turn — the LLM call after the absorption has
     * returned, so the model has provably seen the message. Deliberately this late: a claim whose
     * response was lost leaves the claimed run to execute itself, a lost confirmation costs at
     * worst a duplicate answer — both degrade away from losing the message.
     */
    void confirmOnAssistantTurn() {
        if (unconfirmed.isEmpty()) {
            return;
        }
        try {
            client.markSteered(agentId, runId, List.copyOf(unconfirmed));
            unconfirmed.clear();
        } catch (Exception e) {
            // Kept in the list: the next assistant turn retries.
            log.warn("steering confirmation failed (best-effort): {}", e.getMessage());
        }
    }

    private AgentChatMessage withFraming(AgentChatMessage bare) {
        String base = bare.text() != null ? bare.text() : "";
        return AgentChatMessage.user(steeredPrefix + "\n\n" + base, bare.parts());
    }
}
