package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A connector's settings for one agent, kept on the binding ({@code agent_connections.settings}). The
 * schema is a record the connector declares; the column only stores it. Reading is lenient — an
 * unknown key (a component since removed) is ignored, a missing one is {@code null} — so the record
 * with every component {@code null} is the default. Writing takes the record itself, so a key outside
 * the schema has nowhere to come from.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorSettingsService {

    private final AgentConnectionRepository agentConnectionRepository;

    /** The agent's settings of this connection; the default when there is no agent or no binding. */
    public <T extends Record> T get(UUID agentId, String connectionId, Class<T> type) {
        Map<String, Object> stored = binding(agentId, connectionId)
                .map(AgentConnection::getSettings)
                .orElse(Map.of());
        return JsonUtils.MAPPER.convertValue(stored == null ? Map.of() : stored, type);
    }

    /** Replace the agent's settings of this connection; {@code null} components are not stored. */
    @Transactional
    public <T extends Record> T save(UUID agentId, String connectionId, T settings) {
        AgentConnection binding = binding(agentId, connectionId)
                .orElseThrow(() -> new ConnectorException("The connection is not bound to this agent"));
        Map<String, Object> values = new HashMap<>(JsonUtils.objectToMap(settings));
        values.values().removeIf(Objects::isNull);
        binding.setSettings(values);
        agentConnectionRepository.save(binding);
        return settings;
    }

    private Optional<AgentConnection> binding(UUID agentId, String connectionId) {
        if (agentId == null || connectionId == null) {
            return Optional.empty();
        }
        UUID connection;
        try {
            connection = UUID.fromString(connectionId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return agentConnectionRepository.findActiveBinding(agentId, connection);
    }
}
