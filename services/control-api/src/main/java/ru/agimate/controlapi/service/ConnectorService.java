package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    /**
     * Контракт: вызывается вне активной транзакции, когда {@code ToolCallLog} уже закоммичен —
     * async-исполнитель читает строку из БД и пишет результат. Вызов из транзакции — ошибка
     * вызывающего (исполнитель не увидит лог; tripwire ниже ловит регрессию).
     */
    public void pushToConnector(ToolCallLog toolCallLog) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("pushToConnector called inside an active transaction — "
                    + "the executor may not see the uncommitted tool_call_log row. toolCall={}",
                    toolCallLog.getExternalId());
        }
        Connector connector = connectorRepository.findById(toolCallLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolCallLog.getConnectorCode()));

        // Роутинг по execution locus: BACKEND — исполняем in-proc; EXTERNAL — пушим на устройство;
        // AGENT — исполняет агент, control-api лишь авторизует (молчаливо игнорируем доставку).
        switch (connector.getExecutionLocus()) {
            case BACKEND -> toolExecutionService.executeTool(toolCallLog);
            case EXTERNAL -> {
                // connectionId = connections.id; устройство берём по connection.app_id.
                Connection connection = connectionRepository
                        .findByIdNotDeleted(UUID.fromString(toolCallLog.getConnectionId()))
                        .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + toolCallLog.getConnectionId()));
                var app = appRepository.findByIdAndUserIdNotDeleted(connection.getAppId(), toolCallLog.getUserId())
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + connection.getAppId()));
                // Канал адресуется по app.id (= connectionId, глобально уникален), а не по device_id:
                // device_id задаёт само устройство и не уникален между тенантами — общий device_id у двух
                // пользователей означал бы общий канал и утечку toolCall между ними.
                centrifugoService.publishMessage(
                        "app:" + app.getId(), "toolCall", ToolCallPayload.from(toolCallLog));
            }
            case AGENT -> log.warn("AGENT-locus connector called, ignoring. connectorCode={}, toolCall={}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
            case null -> throw new NotFoundStatusException(
                    "Connector has no execution locus: " + toolCallLog.getConnectorCode());
        }
    }
}
