package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.JobSpec;

import java.util.Map;

/**
 * A connector capability: background jobs. Declarations ({@link #getJobs()}) are materialised into
 * {@code connector_jobs} rows ({@code ConnectorIdentityListener}); execution is dispatched by the
 * scheduler through {@link #executeJob} — including into hidden {@code @Tool} methods (an agent's
 * dynamic jobs).
 */
public interface JobProvider {

    Map<String, JobSpec> getJobs();

    Map<String, Object> executeJob(ConnectorEnv env, String name, Map<String, Object> args);
}
