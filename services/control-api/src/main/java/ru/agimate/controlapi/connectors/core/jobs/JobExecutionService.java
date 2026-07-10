package ru.agimate.controlapi.connectors.core.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.JobProvider;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Исполняет одну итерацию строки {@code connector_jobs}: находит handler по
 * {@code connector_code}, собирает {@link ConnectorEnv} (для integration — со свежими
 * credentials по {@code connectionId}) и диспатчит {@code name}/{@code args}.
 *
 * <p>Вызывается из virtual thread'а scheduler'а вне транзакции — long-poll может держать
 * поток десятки секунд, коннект к БД на это время не занимается.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutionService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ConnectorEnvFactory envFactory;

    public Map<String, Object> executeJob(ConnectorJob row) {
        ConnectorHandler handler = connectorRegistry.getHandler(row.getConnectorCode());
        JobProvider jobProvider = connectorRegistry.getCapability(row.getConnectorCode(), JobProvider.class);
        ConnectorEnv env = buildEnv(handler, row);
        Map<String, Object> args = row.getArgs() == null ? Map.of() : row.getArgs();
        return jobProvider.executeJob(env, row.getName(), args);
    }

    private ConnectorEnv buildEnv(ConnectorHandler handler, ConnectorJob row) {
        if (handler instanceof IntegrationConnectorHandler) {
            // Credentials загружаются свежими на каждый запуск — обновление токена
            // подхватывается без рестарта. Нет/выключены — ошибка в last_error и retry:
            // в норме listener удаляет такие строки, так что это сигнал аномалии.
            Connection connection = connectionRepository
                    .findByIdNotDeleted(parseIdentity(row))
                    .filter(Connection::isActive)
                    .orElseThrow(() -> new ConnectorException(
                            "Connection missing or disabled: " + row.getConnectionId()));
            return envFactory.forConnection(connection, null, null);
        }
        // Полный контекст инициатора (userId/agentId/channelId сохранены в строке при планировании) —
        // динамическая таска агента исполняется так же, как если бы он вызвал тулу сам.
        return envFactory.internal(
                row.getConnectionId(), row.getUserId(), row.getAgentId(), row.getChannelId());
    }

    private static UUID parseIdentity(ConnectorJob row) {
        try {
            return UUID.fromString(row.getConnectionId());
        } catch (Exception e) {
            throw new ConnectorException("Invalid integration connectionId: " + row.getConnectionId());
        }
    }
}
