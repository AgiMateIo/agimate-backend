package ru.agimate.controlapi.connectors.core.dto;

import ru.agimate.controlapi.connectors.core.annotation.Task;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.util.Map;

/**
 * Декларация фоновой задачи коннектора — контракт между {@code getTasks()} и «писателями»
 * в {@code connector_tasks} ({@code ConnectorIdentityListener}, {@code ConnectorBootstrap}).
 *
 * @param name           имя задачи; диспатчится в {@code @Tool}-метод с этим именем
 *                       (включая обычные тулы — см. {@link Task})
 * @param taskType       ONETIME / PERIODIC / CRON
 * @param taskConfig     параметры расписания: {@code intervalSeconds} | {@code cron}, {@code zone}
 * @param taskArgs       аргументы, передаваемые в метод при каждом запуске
 * @param timeoutSeconds лимит одной итерации (lease)
 */
public record TaskSpecification(
        String name,
        ConnectorTaskType taskType,
        Map<String, Object> taskConfig,
        Map<String, Object> taskArgs,
        int timeoutSeconds
) {

    public TaskSpecification {
        taskConfig = taskConfig == null ? Map.of() : Map.copyOf(taskConfig);
        taskArgs = taskArgs == null ? Map.of() : Map.copyOf(taskArgs);
    }
}
