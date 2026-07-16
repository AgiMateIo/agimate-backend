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
 * Листинг триггеров — параллель {@link ru.agimate.controlapi.service.tool.ToolDefinitionService}
 * для триггеров. Каталог отдаёт только type-declared спеки типа коннектора; экземпляр —
 * объединение type-declared с динамическими триггерами {@code connection_triggers} (device-apps),
 * так как разные экземпляры одного коннектора могут открывать разные наборы.
 *
 * <p>Источники мёржатся напрямую (объединение, а не switch по {@code definitionBinding}): type-declared
 * из {@link TriggerProvider}, динамические — из {@code connection_triggers} по {@code connectionId},
 * который проверяется на принадлежность вызывающему (иначе IDOR).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriggerDefinitionService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;

    /** Type-level триггеры типа коннектора (каталог); нет {@link TriggerProvider} — пустой список. */
    public List<TriggerSpecificationResponse> getCatalogTriggers(String connectorCode) {
        if (connectorRegistry.findHandler(connectorCode).isEmpty()) {
            throw new NotFoundStatusException("Connector not found: " + connectorCode);
        }
        return typeTriggers(connectorCode);
    }

    /** Триггеры экземпляра: type-declared ∪ динамические из {@code connection_triggers}. */
    public List<TriggerSpecificationResponse> getConnectionTriggers(UUID userId, UUID connectionId) {
        Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));

        // name — бизнес-ключ; динамические перекрывают одноимённые type-declared.
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

    /** Best-effort: имена property из сырой JSON Schema параметров динамического триггера. */
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
