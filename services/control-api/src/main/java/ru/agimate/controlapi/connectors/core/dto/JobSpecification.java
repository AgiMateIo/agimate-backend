package ru.agimate.controlapi.connectors.core.dto;

import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.util.Map;

/**
 * Декларация фоновой задачи коннектора — контракт между {@code getJobs()} и «писателями»
 * в {@code connector_jobs} ({@code ConnectorIdentityListener}, {@code ConnectorBootstrap}).
 *
 * @param name           имя задачи; диспатчится в {@code @Tool}-метод с этим именем
 *                       (включая обычные тулы — см. {@link Job})
 * @param type       ONETIME / PERIODIC / CRON
 * @param config     параметры расписания: {@code intervalSeconds} | {@code cron}, {@code zone}
 * @param args       аргументы, передаваемые в метод при каждом запуске
 * @param timeoutSeconds лимит одной итерации (lease)
 */
public record JobSpecification(
        String name,
        ConnectorJobType type,
        Map<String, Object> config,
        Map<String, Object> args,
        int timeoutSeconds
) {

    public JobSpecification {
        config = config == null ? Map.of() : Map.copyOf(config);
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
