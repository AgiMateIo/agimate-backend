package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stopping a run at the user's request. Cancellation is cooperative: this service only records the
 * request, and the run learns about it at its next seam — the answer to {@code SaveMessage} (between
 * turns) or to {@code GetToolResult} (while a tool is running). Nothing is interrupted by force; a
 * tool already in flight finishes and records its outcome.
 *
 * <p><b>Only a human cancels.</b> Every entry point is a user-authenticated one under
 * {@code /manage/**} or the ACP session the same user holds; no connector tool reaches this service,
 * and none should. An agent able to stop runs is an agent able to silence another agent, and no ABAC
 * rule makes that safer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunCancellationService {

    private final AgentRunRepository agentRunRepository;
    private final ChannelSessionRepository channelSessionRepository;
    private final ChannelRepository channelRepository;

    /**
     * The outcome of asking a run to stop.
     *
     * @param status    the run's status at the moment of the request — {@code RUNNING}/{@code ENQUEUED}
     *                  means the request was recorded, anything terminal means it arrived too late
     * @param requested whether this call recorded the request; {@code false} both for an already
     *                  finished run and for a repeat press, so the caller cannot tell them apart by
     *                  this flag alone — that is what {@code status} is for
     */
    public record CancelResult(RunStatus status, boolean requested) {

        /** Did the run finish on its own before the request landed? The user must be told, not shown a fake «stopped». */
        public boolean alreadyFinished() {
            return status == RunStatus.DONE || status == RunStatus.FAILED;
        }
    }

    /**
     * Records a stop request for one run. Idempotent, and a terminal run wins: cancelling something
     * that has already happened is not an error but a no-op reporting the actual state.
     */
    @Transactional
    public CancelResult cancelRun(UUID runId, UUID userId) {
        AgentRun run = ownedRun(runId, userId);
        int updated = agentRunRepository.requestCancel(runId, userId, LocalDateTime.now());
        if (updated > 0) {
            log.info("cancel requested for run {} by user {}", runId, userId);
        }
        return new CancelResult(run.getStatus(), updated > 0);
    }

    /**
     * The same for a whole conversation. The session is the queue's partition key, so runs pile up
     * behind the one executing — stopping only the current one would let the next start a second
     * later, which is never what the user meant by «stop».
     *
     * @return how many live runs the request was recorded for
     */
    @Transactional
    public int cancelSession(UUID sessionId, UUID userId) {
        requireOwnedSession(sessionId, userId);
        int updated = agentRunRepository.requestCancelBySession(sessionId, userId, LocalDateTime.now());
        log.info("cancel requested for {} run(s) of session {} by user {}", updated, sessionId, userId);
        return updated;
    }

    /**
     * Ownership gate. A run of someone else's agent reads as absent rather than forbidden — the
     * existence of other users' runs is not disclosed, as everywhere else on this boundary.
     */
    private AgentRun ownedRun(UUID runId, UUID userId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found"));
        if (!run.getAgent().getUserId().equals(userId)) {
            throw new NotFoundStatusException("Run not found");
        }
        return run;
    }

    private void requireOwnedSession(UUID sessionId, UUID userId) {
        ChannelSession session = channelSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(session.getChannelId())
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
        if (!channel.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Channel session not found");
        }
    }
}
