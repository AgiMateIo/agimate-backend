package ru.agimate.controlapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.manage.dto.AgentRunPromptResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentRunResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentRunTurnResponse;
import ru.agimate.controlapi.controller.manage.dto.RunUsageResponse;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageLogRepository;
import ru.agimate.controlapi.database.projections.AgentRunProjection;
import ru.agimate.controlapi.database.projections.RunUsageProjection;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read side of runs for the UI: the listing, one run, its transcript and its starting prompt. The
 * writers stay where they are — {@link AgentRunTurnService} owns the ledger, {@link
 * AgentRunPromptService} the snapshot; this class exists so that all three reads share one ownership
 * gate instead of three copies of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentRunQueryService {

    /** The prompt snapshot is a JSON array of messages — see {@code SavePrompt}. */
    private static final TypeReference<List<Map<String, Object>>> PROMPT_TYPE = new TypeReference<>() {
    };

    private final AgentRunRepository agentRunRepository;
    private final AgentRunTurnRepository turnRepository;
    private final LlmUsageLogRepository usageLogRepository;

    /** Every filter is optional; {@code userId} is not — it is the ownership gate, not a filter. */
    public Page<AgentRunResponse> listRuns(UUID userId, UUID agentId, UUID sessionId, UUID triggerLogId,
                                           String connectorCode, String connectionId, String name,
                                           RunStatus status, int page, int size) {
        return withUsage(agentRunRepository.findRunsWithFilters(
                userId, null, agentId, sessionId, triggerLogId,
                blankToNull(connectorCode), blankToNull(connectionId), blankToNull(name), status,
                PageRequest.of(page, size)));
    }

    /**
     * Token spend for a whole page in one query. Not a correlated aggregate inside the listing: that
     * projection selects the trigger's JSONB payload, and Postgres has no equality operator for
     * {@code jsonb}, so it cannot carry a GROUP BY.
     */
    private Page<AgentRunResponse> withUsage(Page<AgentRunProjection> runs) {
        if (runs.isEmpty()) {
            return runs.map(run -> AgentRunResponse.from(run, RunUsageResponse.NONE));
        }
        Map<UUID, RunUsageResponse> usage = usageLogRepository
                .sumByRunIds(runs.stream().map(AgentRunProjection::getId).toList()).stream()
                .collect(Collectors.toMap(RunUsageProjection::getRunId, RunUsageResponse::from));
        return runs.map(run -> AgentRunResponse.from(
                run, usage.getOrDefault(run.getId(), RunUsageResponse.NONE)));
    }

    /**
     * One run — the same row the listing returns, narrowed to a key. Deliberately the same query: the
     * details of a run and a row of the listing are the same fields, and the day one of them gains
     * another, a second projection would quietly stop matching.
     */
    public AgentRunResponse getRun(UUID runId, UUID userId) {
        return withUsage(agentRunRepository.findRunsWithFilters(
                        userId, runId, null, null, null, null, null, null, null, PageRequest.of(0, 1)))
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
    }

    /**
     * The run's transcript, newest turn first. Uncapped: this view exists to answer «what actually
     * happened», which is the one question a truncated tool output cannot answer — the caps belong to
     * the context assembly, not here.
     */
    public Page<AgentRunTurnResponse> listTurns(UUID runId, UUID userId, int page, int size) {
        AgentRun run = ownedRun(runId, userId);
        return turnRepository.findByRunIdOrderByTurnIndexDesc(run.getId(), PageRequest.of(page, size))
                .map(AgentRunTurnResponse::from);
    }

    /**
     * The run's input: the message list exactly as it went into the first LLM call. Turn 0 of the
     * ledger is the user's question alone, so everything the model was actually given — system blocks,
     * history, the ephemeral memory notes — is visible only here.
     *
     * <p>The tree is converted to plain collections on the way out. It is stored as a Jackson 2
     * {@code JsonNode}, and the converter serialising the response is Jackson 3: to it that node is an
     * unknown POJO, not a JSON tree. Plain lists and maps are the neutral ground between the two.
     */
    public AgentRunPromptResponse getPrompt(UUID runId, UUID userId) {
        AgentRun run = ownedRun(runId, userId);
        if (run.getPrompt() == null) {
            return new AgentRunPromptResponse(runId, null);
        }
        return new AgentRunPromptResponse(runId, JsonUtils.MAPPER.convertValue(run.getPrompt(), PROMPT_TYPE));
    }

    /** Someone else's run reads as absent, not as forbidden — its existence is not ours to disclose. */
    private AgentRun ownedRun(UUID runId, UUID userId) {
        return agentRunRepository.findById(runId)
                .filter(run -> run.getAgent().getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
