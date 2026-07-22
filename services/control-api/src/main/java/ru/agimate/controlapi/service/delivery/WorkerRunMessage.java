package ru.agimate.controlapi.service.delivery;

/**
 * DBOS-payload запуска рана (протокол v2): только адресация — агент и ран
 * (agent_runs.id == DBOS workflow id). Всё остальное (блоки промпта, тулы, история,
 * каналы) воркер забирает одним {@code GetRunContext(agent_id, trigger_id)}.
 */
public record WorkerRunMessage(String agentId, String runId) {
}
