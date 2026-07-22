package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DBOS-payload запуска рана (протокол v2): только адресация — агент и ран
 * (agent_runs.id == DBOS workflow id). Всё остальное (блоки промпта, тулы, история,
 * каналы) воркер забирает одним {@code GetRunContext(agent_id, run_id)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentMessage(String agentId, String runId) {
}
