package ru.agimate.controlapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.util.UUID;

/**
 * Snapshot of a run's starting prompt ({@code agent_runs.prompt}): the message list exactly as it went
 * into the first LLM call. The worker writes it once before the loop ({@code SavePrompt}), and later
 * turns go to {@link AgentRunTurnService}. Stored as an opaque JSON tree — observability, not a
 * projection.
 *
 * <p>First-write-wins: the snapshot is written only while it is still empty. The worker sends
 * SavePrompt as an ordinary (non-durable) call — a replay or retry repeats the write, but the second
 * write does not overwrite the first (the context window may have shifted, and what is needed is the
 * snapshot at the run's start). There are no races: the queue is partitioned by session
 * (concurrency=1) and a replay is sequential.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunPromptService {

    private final AgentRunRepository agentRunRepository;

    public record SaveResult(boolean stored) {}

    @Transactional
    public SaveResult save(UUID agentId, UUID runId, String promptJson) {
        if (promptJson == null || promptJson.isBlank()) {
            throw new BadRequestStatusException("prompt_json is required");
        }
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
        if (!run.getAgent().getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + runId + " does not belong to agent " + agentId);
        }
        if (run.getPrompt() != null) {
            log.debug("prompt snapshot already present run={}", runId);
            return new SaveResult(false);
        }
        JsonNode prompt = JsonUtils.toJsonNode(promptJson);
        if (prompt == null) {
            throw new BadRequestStatusException("prompt_json is not valid JSON");
        }
        run.setPrompt(prompt);
        return new SaveResult(true);
    }
}
