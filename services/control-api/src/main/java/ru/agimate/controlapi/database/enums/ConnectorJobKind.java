package ru.agimate.controlapi.database.enums;

/**
 * Who created a {@code connector_jobs} row and how it is managed.
 * <ul>
 *   <li>{@link #SYSTEM} — a declarative connector job ({@code getJobs()}): the row is owned by the
 *       reconcile sync (upsert/delete by the business key {@code (connector_code, connectionId,
 *       name)}); the business key's uniqueness in the database applies to these rows only.
 *       {@code agent_id IS NULL}.</li>
 *   <li>{@link #AGENT} — scheduled by an agent at runtime (e.g. {@code time.schedule}); identified
 *       by its own {@code id}, and one agent may own many such rows. {@code agent_id} is the
 *       initiator (and the delivery target for {@code time.fire}).</li>
 *   <li>{@link #USER} — created by a user through the manage API (creation is not implemented yet,
 *       the value is reserved); {@code agent_id} is the target agent when the job is addressed.</li>
 * </ul>
 */
public enum ConnectorJobKind {
    SYSTEM,
    USER,
    AGENT
}
