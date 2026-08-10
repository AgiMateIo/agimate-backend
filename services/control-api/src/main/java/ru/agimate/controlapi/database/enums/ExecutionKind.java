package ru.agimate.controlapi.database.enums;

/**
 * Where a connector's tools are executed ({@code connectors.execution_kind}) — the only axis the call
 * dispatch branches on ({@code ConnectorService.pushToConnector}).
 *
 * <ul>
 *   <li>{@link #BACKEND} — executed in-proc by our own infrastructure (the handler's {@code @Tool}
 *       method). This includes connectors that reach external APIs from the inside (telegram, mcp,
 *       media): «external over the network» is an implementation detail of the tool service, not an
 *       axis of the model.</li>
 *   <li>{@link #APP} — executed by a program that connects to us ({@code apps}): the call is pushed
 *       into the app's channel and the result arrives asynchronously. Whether that program runs on a
 *       phone, a laptop or a server is not an axis — hence APP rather than the former DEVICE.</li>
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
    APP,
    LOOPBACK
}
