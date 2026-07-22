package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.List;
import java.util.UUID;

/**
 * Канонический full-fidelity журнал ходов рана ({@code agent_run_turns}): по одной записи на
 * AgentChatMessage воркера (assistant/tool), без капов — в отличие от капнутой канальной проекции
 * {@code channel_session_messages}. Пишется для всех ранов, включая direct ({@code session_id} = null).
 *
 * <p>Идемпотентность — UNIQUE {@code (run_id, turn_index)} через ON CONFLICT DO NOTHING: воркер шлёт
 * SaveTurn обычным (не durable) вызовом, реплей/ретрай повторяет ту же пару без дубля.
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
                           boolean thinking, List<ToolTurnRecord.Call> toolCalls,
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
                emptyToNull(text), thinking, callsJson, resultsJson,
                emptyToNull(finishReason), emptyToNull(model), emptyToNull(callId));

        boolean duplicate = inserted == 0;
        if (duplicate) {
            log.debug("saveTurn duplicate run={} turn={} role={}", runId, turnIndex, role);
        }
        return new SaveResult(duplicate);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
