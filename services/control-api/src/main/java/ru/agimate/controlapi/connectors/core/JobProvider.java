package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.JobSpec;

import java.util.Map;

/**
 * Capability коннектора: фоновые таски. Декларации ({@link #getJobs()}) материализуются в строки
 * {@code connector_jobs} ({@code ConnectorIdentityListener}); исполнение диспатчит scheduler через
 * {@link #executeJob} — в том числе в скрытые {@code @Tool}-методы (динамические таски агента).
 */
public interface JobProvider {

    Map<String, JobSpec> getJobs();

    Map<String, Object> executeJob(ConnectorContext context, String name, Map<String, Object> args);
}
