package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stopping a run at the user's request. Cooperative: this only records the request, and the run reads
 * it off its next seam — the answer to {@code SaveMessage} or {@code GetToolResult}.
 *
 * <p><b>Only a human cancels.</b> Connector tools must not reach this service, and none should: an
 * agent able to stop runs is an agent able to silence another agent, and no ABAC rule makes that
 * safer. The one deliberate exception is the platform connector's {@code cancel_run}/
 * {@code cancel_session}: they run on behalf of the agent's human owner ({@code env.userId}, the
 * same identity the /manage surface acts as), so the canceller is the owner, not the agent — see
 * docs/decisions/platform-admin-mcp.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunCancellationService {

    private final AgentRunRepository agentRunRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final ChannelRepository channelRepository;

    /**
     * @param status    the run's status when the request landed; the terminal one arrives later
     * @param requested this call recorded the request — {@code false} for a repeat press as well as
     *                  for a finished run, which {@code status} is there to tell apart
     */
    public record CancelResult(RunStatus status, boolean requested) {

        /** Finished on its own first — the user must be told, not shown a fake «stopped». */
        public boolean alreadyFinished() {
            return status == RunStatus.DONE || status == RunStatus.FAILED;
        }
    }

    /** Idempotent, and a terminal run wins: cancelling what already happened is a no-op, not an error. */
    @Transactional
    public CancelResult cancelRun(UUID runId, UUID userId) {
        AgentRun run = ownedRun(runId, userId);
        int updated = agentRunRepository.requestCancel(runId, LocalDateTime.now());
        if (updated > 0) {
            log.info("cancel requested for run {} by user {}", runId, userId);
        }
        return new CancelResult(run.getStatus(), updated > 0);
    }

    /**
     * The same for a whole conversation: the session is the queue's partition, so runs pile up behind
     * the one executing and stopping only that one would let the next start a second later.
     *
     * @return how many live runs the request was recorded for
     */
    @Transactional
    public int cancelSession(UUID sessionId, UUID userId) {
        requireOwnedSession(sessionId, userId);
        int updated = agentRunRepository.requestCancelBySession(sessionId, LocalDateTime.now());
        log.info("cancel requested for {} run(s) of session {} by user {}", updated, sessionId, userId);
        return updated;
    }

    /**
     * The same stop, asked for from inside the conversation — the {@code /stop} command in a channel.
     * There is no ownership check and cannot be: the sender is a messenger account, not a platform
     * user, and who exactly pressed stop in a group chat is not something we know — which is why
     * nothing about the actor is recorded either.
     *
     * <p>That widens «only the owner cancels» to «whoever can write into this chat cancels», and the
     * widening is deliberate: someone able to message the agent can already make it act and spend
     * money, so stopping it is the smaller power. The caller must have resolved the channel already —
     * that is what stands in for the gate.
     */
    @Transactional
    public int cancelSessionFromChannel(UUID sessionId) {
        int updated = agentRunRepository.requestCancelBySession(sessionId, LocalDateTime.now());
        log.info("cancel requested for {} run(s) of session {} from the channel", updated, sessionId);
        return updated;
    }

    /** Someone else's run reads as absent, not forbidden: their existence is not disclosed. */
    private AgentRun ownedRun(UUID runId, UUID userId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found"));
        if (!run.getAgent().getUserId().equals(userId)) {
            throw new NotFoundStatusException("Run not found");
        }
        return run;
    }

    private void requireOwnedSession(UUID sessionId, UUID userId) {
        AgentSession session = agentSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
        // Ownership first: a foreign row must read as not found, not as a different error that would
        // confirm its existence and scope.
        if (!session.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Channel session not found");
        }
        // A connection-scoped session has no channel and cannot be stopped through it; the listing
        // shows every scope, so a cancel must not pretend the row is missing.
        if (session.getChannelId() == null) {
            throw new BadRequestStatusException("Only channel sessions can be cancelled");
        }
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(session.getChannelId())
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
        if (!channel.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Channel session not found");
        }
    }
}
