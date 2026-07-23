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
 * Снимок стартового промпта рана ({@code agent_runs.prompt}): список сообщений ровно как он ушёл в
 * первый LLM-вызов. Пишет воркер один раз перед циклом ({@code SavePrompt}), дальнейшие ходы идут в
 * {@link AgentRunTurnService}. Хранится opaque JSON-деревом — наблюдаемость, не проекция.
 *
 * <p>First-write-wins: снимок пишется только если ещё пуст. Воркер шлёт SavePrompt обычным (не
 * durable) вызовом — реплей/ретрай повторяет запись, но повторная запись не перезатирает первую
 * (окно контекста могло сдвинуться, нужен снимок на старт рана). Гонок нет: очередь партиционирована
 * по сессии (concurrency=1), реплей последователен.
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
