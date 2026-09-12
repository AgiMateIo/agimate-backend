package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.JobSpec;

import java.util.Map;

/**
 * A connector capability: background jobs. Declarations ({@link #getJobs(ConnectorEnv)}, per instance)
 * are materialised into {@code connector_jobs} rows by {@link ConnectorIdentityListener}; execution is
 * dispatched by the scheduler through {@link #executeJob} — including into hidden {@code @Tool} methods
 * (an agent's dynamic jobs).
 */
public interface JobProvider {

    Map<String, JobSpec> getJobs();

    /**
     * Jobs of one particular connector instance — what actually gets materialised for it, both by the
     * lifecycle listener and by the startup re-sync. By default equal to {@link #getJobs()}; a
     * connector whose job only makes sense for some instances (the MCP token refresh: nothing to renew
     * behind a static token) returns a subset here. The context carries only connectionId — no
     * decrypted credentials.
     */
    default Map<String, JobSpec> getJobs(ConnectorEnv env) {
        return getJobs();
    }

    Map<String, Object> executeJob(ConnectorEnv env, String name, Map<String, Object> args);
}
