package ru.agimate.controlapi.connectors.core.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
 * Executes one iteration of a {@code connector_jobs} row: finds the handler by
 * {@code connector_code}, assembles a {@link ConnectorEnv} (for an integration — with fresh
 * credentials for the {@code connectionId}) and dispatches {@code name}/{@code args}.
 *
 * <p>Called from the scheduler's virtual thread outside a transaction — a long poll can hold the
 * thread for tens of seconds, and a database connection is not occupied for that time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobExecutionService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ConnectorEnvFactory envFactory;

    public Map<String, Object> executeJob(ConnectorJob row) {
        ConnectorHandler handler = connectorRegistry.getHandler(row.getConnectorCode());
        JobProvider jobProvider = ConnectorRegistry.capability(handler, JobProvider.class);
        ConnectorEnv env = buildEnv(handler, row);
        Map<String, Object> args = row.getArgs() == null ? Map.of() : row.getArgs();
        return jobProvider.executeJob(env, row.getName(), args);
    }

    private ConnectorEnv buildEnv(ConnectorHandler handler, ConnectorJob row) {
        if (handler instanceof IntegrationConnectorHandler) {
            // The credentials are loaded fresh on every run — a token refresh is picked up without a restart.
            // Missing or disabled ones mean an error into last_error plus a retry: normally the listener deletes
            // such rows, so this is a signal of an anomaly.
            Connection connection = connectionRepository
                    .findByIdNotDeleted(parseIdentity(row))
                    .filter(Connection::isActive)
                    .orElseThrow(() -> new ConnectorException(
                            "Connection missing or disabled: " + row.getConnectionId()));
            return envFactory.forConnection(connection, null, null, null);
        }
        // The initiator's full context (userId/agentId/channelId/sessionId were saved into the row at scheduling
        // time) — an agent's dynamic job executes exactly as if the agent had called the tool itself. A job has no
        // runId: this is deferred execution outside the initiating run.
        return envFactory.internal(
                row.getConnectionId(), row.getUserId(), row.getAgentId(), null, row.getChannelId(),
                row.getSessionId());
    }

    private static UUID parseIdentity(ConnectorJob row) {
        try {
            return UUID.fromString(row.getConnectionId());
        } catch (Exception e) {
            throw new ConnectorException("Invalid integration connectionId: " + row.getConnectionId());
        }
    }
}
