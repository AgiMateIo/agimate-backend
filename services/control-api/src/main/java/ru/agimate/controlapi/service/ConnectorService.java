package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.ToolCallPayload;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    private final ConnectorRepository connectorRepository;

    private final ConnectionRepository connectionRepository;

    private final AppRepository appRepository;

    private final CentrifugoService centrifugoService;

    private final ToolExecutionService toolExecutionService;

    /**
     * The contract: called outside an active transaction, once the {@code ToolCallLog} is already
     * committed — the async executor reads the row from the database and writes the result. Calling it
     * from inside a transaction is the caller's bug (the executor would not see the log; the tripwire
     * below catches the regression).
     */
    public void pushToConnector(ToolCallLog toolCallLog) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("pushToConnector called inside an active transaction — "
                    + "the executor may not see the uncommitted tool_call_log row. toolCall={}",
                    toolCallLog.getExternalId());
        }
        Connector connector = connectorRepository.findById(toolCallLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolCallLog.getConnectorCode()));

        switch (connector.getExecutionKind()) {
            // In-proc: the handler's @Tool method; outbound calls (the telegram/mcp APIs) happen inside the tool service.
            case BACKEND -> toolExecutionService.executeTool(toolCallLog);
            // The executor connects to us itself — the call is pushed into the device's channel.
            case DEVICE -> pushToApp(toolCallLog);
            // The calling agent executes it (/tool/check + /tool/result) — dispatching here is the caller's bug.
            case LOOPBACK -> throw new BadRequestStatusException(
                    "Tools of connector '" + toolCallLog.getConnectorCode()
                            + "' execute on the caller side; use /tool/check and report via /tool/result");
            case null -> throw new NotFoundStatusException(
                    "Connector has no execution kind: " + toolCallLog.getConnectorCode());
        }
    }

    /** Delivery to an INBOUND executor: a push into the application's channel. */
    private void pushToApp(ToolCallLog toolCallLog) {
        // connectionId = connections.id; the device is taken by connection.app_id.
        Connection connection = connectionRepository
                .findByIdNotDeleted(UUID.fromString(toolCallLog.getConnectionId()))
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + toolCallLog.getConnectionId()));
        var app = appRepository.findByIdAndUserIdNotDeleted(connection.getAppId(), toolCallLog.getUserId())
                .orElseThrow(() -> new NotFoundStatusException("App not found: " + connection.getAppId()));
        // The channel is addressed by app.id (= connectionId, globally unique) rather than by device_id:
        // device_id is set by the device itself and is not unique across tenants — a device_id shared by two
        // users would mean a shared channel and a toolCall leaking between them.
        centrifugoService.publishMessage(
                "app:" + app.getId(), "toolCall", ToolCallPayload.from(toolCallLog));
    }
}
