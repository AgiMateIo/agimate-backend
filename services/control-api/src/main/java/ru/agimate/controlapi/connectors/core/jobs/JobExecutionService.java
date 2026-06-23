package ru.agimate.controlapi.connectors.core.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorContextFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Исполняет одну итерацию строки {@code connector_jobs}: находит handler по
 * {@code connector_code}, собирает {@link ConnectorContext} (для integration — со свежими
 * credentials по {@code identity}) и диспатчит {@code name}/{@code args}.
 *
 * <p>Вызывается из virtual thread'а scheduler'а вне транзакции — long-poll может держать
 * поток десятки секунд, коннект к БД на это время не занимается.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobExecutionService {

    private final ConnectorRegistry connectorRegistry;
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final ConnectorContextFactory contextFactory;

    public Map<String, Object> executeJob(ConnectorJob row) {
        ConnectorHandler handler = connectorRegistry.getHandler(row.getConnectorCode());
        ConnectorContext context = buildContext(handler, row);
        Map<String, Object> args = row.getArgs() == null ? Map.of() : row.getArgs();
        return handler.executeJob(context, row.getName(), args);
    }

    private ConnectorContext buildContext(ConnectorHandler handler, ConnectorJob row) {
        if (handler instanceof IntegrationConnectorHandler) {
            // Credentials загружаются свежими на каждый запуск — обновление токена
            // подхватывается без рестарта. Нет/выключены — ошибка в last_error и retry:
            // в норме listener удаляет такие строки, так что это сигнал аномалии.
            IntegrationCredentials credentials = integrationCredentialsRepository
                    .findByIdNotDeleted(parseIdentity(row))
                    .filter(IntegrationCredentials::isActive)
                    .orElseThrow(() -> new ConnectorException(
                            "Integration credentials missing or disabled: " + row.getIdentity()));
            return contextFactory.forIntegration(credentials, null, null);
        }
        // Полный контекст инициатора (userId/agentId/channelId сохранены в строке при планировании) —
        // динамическая таска агента исполняется так же, как если бы он вызвал тулу сам.
        return contextFactory.internal(
                row.getIdentity(), row.getUserId(), row.getAgentId(), row.getChannelId());
    }

    private static UUID parseIdentity(ConnectorJob row) {
        try {
            return UUID.fromString(row.getIdentity());
        } catch (Exception e) {
            throw new ConnectorException("Invalid integration identity: " + row.getIdentity());
        }
    }
}
