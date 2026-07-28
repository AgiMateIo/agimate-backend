package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.controller.manage.dto.TriggerSpecificationResponse;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listing of triggers — the parallel of
 * {@link ru.agimate.controlapi.service.tool.ToolDefinitionService} for triggers. The catalogue returns
 * only the type-declared specs of a connector type; an instance returns the union of the type-declared
 * ones with the dynamic triggers from {@code connection_triggers} (device apps), because different
 * instances of one connector may expose different sets.
 *
 * <p>The sources are merged directly (a union, not a switch on {@code definitionBinding}):
 * type-declared ones from {@link TriggerProvider}, dynamic ones from {@code connection_triggers} by
 * {@code connectionId}, which is checked to belong to the caller (otherwise it is an IDOR).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriggerDefinitionService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;

    /** Type-level triggers of a connector type (the catalogue); no {@link TriggerProvider} — an empty list. */
    public List<TriggerSpecificationResponse> getCatalogTriggers(String connectorCode) {
        if (connectorRegistry.findHandler(connectorCode).isEmpty()) {
            throw new NotFoundStatusException("Connector not found: " + connectorCode);
        }
        return typeTriggers(connectorCode);
    }

    /** An instance's triggers: type-declared ∪ the dynamic ones from {@code connection_triggers}. */
    public List<TriggerSpecificationResponse> getConnectionTriggers(UUID userId, UUID connectionId) {
        Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));

        // name is the business key; dynamic ones override type-declared entries of the same name.
        Map<String, TriggerSpecificationResponse> merged = new LinkedHashMap<>();
        typeTriggers(connection.getConnectorCode()).forEach(t -> merged.put(t.name(), t));
        connectionTriggerRepository.findActiveByConnectionId(connectionId)
                .forEach(t -> merged.put(t.getName(), toResponse(t)));
        return List.copyOf(merged.values());
    }

    private List<TriggerSpecificationResponse> typeTriggers(String connectorCode) {
        return connectorRegistry.findCapability(connectorCode, TriggerProvider.class)
                .map(TriggerProvider::getTriggers)
                .orElseGet(Map::of)
                .entrySet().stream()
                .map(e -> TriggerSpecificationResponse.from(e.getKey(), e.getValue()))
                .toList();
    }

    private static TriggerSpecificationResponse toResponse(ConnectionTrigger trigger) {
        return new TriggerSpecificationResponse(
                trigger.getName(),
                trigger.getDescription(),
                schemaParamNames(trigger.getParamsSchema()));
    }

    /** Best-effort: the property names from the raw JSON Schema of a dynamic trigger's parameters. */
    private static List<String> schemaParamNames(String paramsSchema) {
        if (paramsSchema == null || paramsSchema.isBlank()) {
            return List.of();
        }
        try {
            JsonNode props = JsonUtils.MAPPER.readTree(paramsSchema).path("properties");
            if (!props.isObject()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            props.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (Exception e) {
            log.debug("Unparseable params_schema for dynamic trigger, returning no params", e);
            return List.of();
        }
    }
}
