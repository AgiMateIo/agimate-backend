package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A task as the tasks extension shows it: the seed state answered to {@code tools/call}
 * ({@code resultType: "task"}) and the {@code tasks/get} view ({@code resultType: "complete"},
 * with the tool result inlined once completed). {@code failed} is never produced: a tool failure
 * is a completed task whose result carries {@code isError} — the extension reserves {@code failed}
 * for JSON-RPC faults, and this server has none that would outlive the original request.
 *
 * @param ttlMs  milliseconds from {@code createdAt} after which the server answers "expired"
 * @param result inlined for a completed task only; a cancelled one withholds it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResult(
        String resultType,
        String taskId,
        String status,
        String createdAt,
        String lastUpdatedAt,
        Long ttlMs,
        Integer pollIntervalMs,
        ToolCallResult result
) implements McpResult {

    public static TaskResult created(ToolCallLog task, long ttlMs, int pollIntervalMs) {
        return new TaskResult("task", task.getExternalId(), "working",
                iso(task.getCreatedAt()), iso(task.getUpdatedAt()), ttlMs, pollIntervalMs, null);
    }

    public static TaskResult working(ToolCallLog task, long ttlMs, int pollIntervalMs) {
        return new TaskResult("complete", task.getExternalId(), "working",
                iso(task.getCreatedAt()), iso(task.getUpdatedAt()), ttlMs, pollIntervalMs, null);
    }

    public static TaskResult cancelled(ToolCallLog task, long ttlMs, int pollIntervalMs) {
        return new TaskResult("complete", task.getExternalId(), "cancelled",
                iso(task.getCreatedAt()), iso(task.getUpdatedAt()), ttlMs, pollIntervalMs, null);
    }

    public static TaskResult completed(ToolCallLog task, ToolCallResult result,
                                       long ttlMs, int pollIntervalMs) {
        return new TaskResult("complete", task.getExternalId(), "completed",
                iso(task.getCreatedAt()), iso(task.getUpdatedAt()), ttlMs, pollIntervalMs, result);
    }

    /**
     * Stamps are JVM wall clock ({@link ru.agimate.common.persistence.BaseEntity}), so the JVM
     * zone is the honest lens turning them into an instant.
     */
    private static String iso(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toString();
    }
}
