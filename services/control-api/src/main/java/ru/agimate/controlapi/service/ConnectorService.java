package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    public void pushToConnector(ToolCallLog toolCallLog) {
        Connector connector = connectorRepository.findById(toolCallLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolCallLog.getConnectorCode()));

        // Роутинг по execution locus: BACKEND — исполняем in-proc; EXTERNAL — пушим на устройство;
        // AGENT — исполняет агент, control-api лишь авторизует (молчаливо игнорируем доставку).
        switch (connector.getExecutionLocus()) {
            case BACKEND -> afterCommitOrNow(() -> toolExecutionService.executeTool(toolCallLog));
            case EXTERNAL -> {
                // connectionId = connections.id; устройство берём по connection.app_id.
                Connection connection = connectionRepository
                        .findByIdNotDeleted(UUID.fromString(toolCallLog.getConnectionId()))
                        .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + toolCallLog.getConnectionId()));
                var app = appRepository.findByIdAndUserIdNotDeleted(connection.getAppId(), toolCallLog.getUserId())
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + connection.getAppId()));
                afterCommitOrNow(() -> centrifugoService.publishMessage(
                        "device:" + app.getDeviceId(), "toolCall", ToolCallPayload.from(toolCallLog)));
            }
            case AGENT -> log.warn("AGENT-locus connector called, ignoring. connectorCode={}, toolCall={}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
            case null -> throw new NotFoundStatusException(
                    "Connector has no execution locus: " + toolCallLog.getConnectorCode());
        }
    }

    /**
     * Исполнение диспатчится только после коммита транзакции, создавшей {@code ToolCallLog}.
     * Прямой gRPC-путь ({@code ExecuteToolAsync}) внешней транзакции не имеет — лог уже
     * закоммичен, диспатчим сразу. Канальная же доставка зовёт {@code processToolCall} изнутри
     * {@code @Transactional} {@code ChannelMessageOutboundService.send} — createLog присоединяется
     * к её транзакции, и немедленный {@code @Async}-диспатч успевает исполниться и записать
     * результат раньше, чем строка лога видна ({@code recordOutput} → NotFound, finish_at
     * теряется навсегда).
     */
    private static void afterCommitOrNow(Runnable dispatch) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }
}
