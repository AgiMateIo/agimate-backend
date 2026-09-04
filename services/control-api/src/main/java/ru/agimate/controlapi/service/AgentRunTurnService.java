package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.List;
import java.util.UUID;

/**
 * The canonical full-fidelity journal of a run's turns ({@code agent_run_turns}): one record per worker
 * AgentChatMessage (inbound/assistant/tool), uncapped — unlike the capped channel projection
 * {@code channel_session_messages}. Written for every run, including direct ones
 * ({@code session_id} = null).
 *
 * <p>Idempotency is UNIQUE {@code (run_id, turn_index)} through ON CONFLICT DO NOTHING: the worker
 * sends SaveTurn as an ordinary (non-durable) call, and a replay or retry repeats the same pair with no
 * duplicate.
 *
 * <p>The ledger is also what the history of later runs is assembled from
 * ({@code RunHistoryAssembler}), which is why {@link #isLedgerIntact} exists: the write is
 * best-effort, and a hole must be found once, when the run finishes, rather than on every assembly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunTurnService {

    private final AgentRunRepository agentRunRepository;
    private final AgentRunTurnRepository turnRepository;

    public record SaveResult(boolean duplicate) {}

    @Transactional
    public SaveResult save(UUID agentId, UUID runId, int turnIndex, AgentTurnRole role, String text,
                           String thinkingText, List<ToolTurnRecord.Call> toolCalls,
                           List<ToolTurnRecord.Result> toolResults,
                           String finishReason, String model, String callId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
        if (!run.getAgent().getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + runId + " does not belong to agent " + agentId);
        }

        String callsJson = toolCalls == null || toolCalls.isEmpty()
                ? null : JsonUtils.writeValueAsString(toolCalls);
        String resultsJson = toolResults == null || toolResults.isEmpty()
                ? null : JsonUtils.writeValueAsString(toolResults);

        int inserted = turnRepository.insertIgnoreConflict(
                runId, run.getSessionId(), agentId, turnIndex, role.name(),
                emptyToNull(text), emptyToNull(thinkingText), callsJson, resultsJson,
                emptyToNull(finishReason), emptyToNull(model), emptyToNull(callId));

        boolean duplicate = inserted == 0;
        if (duplicate) {
            log.debug("saveTurn duplicate run={} turn={} role={}", runId, turnIndex, role);
        }
        return new SaveResult(duplicate);
    }

    /**
     * One turn by its ledger key — what a DBOS replay reads instead of a checkpoint ({@code GetTurn}).
     * Ownership is checked on the turn row itself: it carries {@code agent_id}, so no join is needed.
     */
    @Transactional(readOnly = true)
    public AgentRunTurn get(UUID agentId, UUID runId, int turnIndex) {
        AgentRunTurn turn = turnRepository.findByRunIdAndTurnIndex(runId, turnIndex)
                .orElseThrow(() -> new NotFoundStatusException("Turn not found: " + runId + "/" + turnIndex));
        if (!turn.getAgentId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + runId + " does not belong to agent " + agentId);
        }
        return turn;
    }

    /**
     * Whether the run's ledger can be replayed as the history of a later run. Called once, when the
     * run finishes — {@code SaveTurn} is best-effort, and a hole here becomes a {@code tool_use} with
     * no {@code tool_result} in someone else's context, which providers reject whole.
     *
     * <p>Two checks, both off the unique key. Contiguity: {@code turn_index} advances
     * deterministically, so a gap shows up as {@code count != last + 1}. Pairing: the last turn must
     * not be an assistant that called tools and never got its answer — that happens when a run dies
     * between the call and the result.
     */
    @Transactional(readOnly = true)
    public boolean isLedgerIntact(UUID runId) {
        AgentRunTurn last = turnRepository.findFirstByRunIdOrderByTurnIndexDesc(runId).orElse(null);
        if (last == null) {
            // No ledger at all: nothing to replay, and nothing broken either.
            return true;
        }
        long count = turnRepository.countByRunId(runId);
        if (count != last.getTurnIndex() + 1) {
            log.warn("turn ledger has a gap run={} turns={} last={}", runId, count, last.getTurnIndex());
            return false;
        }
        if (last.getRole() == AgentTurnRole.ASSISTANT && last.getToolCalls() != null
                && !last.getToolCalls().isEmpty()) {
            log.warn("turn ledger ends on an unanswered tool call run={} last={}", runId, last.getTurnIndex());
            return false;
        }
        return true;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
