package ru.agimate.controlapi.connectors.core.dto;

import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.util.Map;

/**
 * Declaration of a connector's background job — the contract between {@code getJobs()} and the
 * «writers» into {@code connector_jobs} ({@code ConnectorIdentityListener}, {@code ConnectorBootstrap}).
 *
 * @param name           job name; dispatched to the {@code @Tool} method of the same name (ordinary
 *                       tools included — see {@link Job})
 * @param type       ONETIME / PERIODIC / CRON
 * @param config     schedule parameters: {@code intervalSeconds} | {@code cron}, {@code zone}
 * @param args       arguments passed into the method on every run
 * @param timeoutSeconds limit of a single iteration (the lease)
 */
public record JobSpec(
        String name,
        ConnectorJobType type,
        Map<String, Object> config,
        Map<String, Object> args,
        int timeoutSeconds
) {

    public JobSpec {
        config = config == null ? Map.of() : Map.copyOf(config);
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
