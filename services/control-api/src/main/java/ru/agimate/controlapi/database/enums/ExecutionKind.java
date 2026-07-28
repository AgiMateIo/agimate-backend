package ru.agimate.controlapi.database.enums;

/**
 * How a connector's tools are executed ({@code connectors.execution_kind}) — the only axis the call
 * dispatch branches on ({@code ConnectorService.pushToConnector}).
 *
 * <ul>
 *   <li>{@link #BACKEND} — executed in-proc by our own infrastructure (the handler's {@code @Tool}
 *       method). This includes connectors that reach external APIs from the inside (telegram, mcp,
 *       media): «external over the network» is an implementation detail of the tool service, not an
 *       axis of the model.</li>
 *   <li>{@link #DEVICE} — executed by a device (app): the call is pushed to the device's channel and
 *       the result arrives asynchronously.</li>
 *   <li>{@link #LOOPBACK} — executed by the calling agent itself (claude-code): dispatching here is a
 *       bug, the agent collects calls by looping over {@code /tool/check} + {@code /tool/result}.</li>
 * </ul>
 *
 * <p>The former {@code execution_locus × transport_direction} pair encoded these same three cases
 * (BACKEND and DELEGATED×OUTBOUND dispatched identically); the «our infra vs external platform»
 * distinction is informational and lives in the docs, not in the data.
 */
public enum ExecutionKind {
    BACKEND,
    DEVICE,
    LOOPBACK
}
