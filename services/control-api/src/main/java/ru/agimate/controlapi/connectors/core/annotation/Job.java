package ru.agimate.controlapi.connectors.core.annotation;

import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Tool} method as a declarative background job of the connector: the method forms a
 * {@link JobSpec} in {@code getJobs()} with the schedule taken from the annotation's attributes.
 * When a connector instance is materialised, the reconcile sync creates a {@code connector_jobs} row
 * for it ({@code kind=SYSTEM}, one per connectionId, with no initiating agent).
 *
 * <p>A declarative job is always hidden from the LLM (absent from {@code getTools()}, unreachable
 * through {@code executeTool}) — it is a background process, not an agent's tool. For a hidden
 * dispatch target that is scheduled dynamically (rows with {@code kind=AGENT}, e.g. {@code time.fire})
 * {@code @Job} is not needed — mark the ordinary {@code @Tool} as {@code @Tool(internal = true)},
 * otherwise reconcile would create a background SYSTEM row for it with no initiator.
 *
 * <p>{@code executeJob} can call any {@code @Tool} method, so «a tool call on a schedule» needs no
 * separate job method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Job {

    ConnectorJobType type() default ConnectorJobType.PERIODIC;

    /** Interval for {@code PERIODIC}; {@code 0} — an immediate repeat (the long-poll pattern). */
    long intervalSeconds() default 0;

    /** Spring cron expression (6 fields, with seconds) for {@code CRON}. */
    String cron() default "";

    String zone() default "UTC";

    /** Limit of a single iteration in seconds; once the lease expires the row is picked up again. */
    int timeoutSeconds() default 300;
}
