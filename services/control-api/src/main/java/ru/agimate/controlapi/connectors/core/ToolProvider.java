package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

import java.util.Map;

/**
 * Capability коннектора: тулы, доступные LLM. Реализуется фасадом (обычно через
 * {@link BaseConnectorHandler}, который строит спеки рефлексией по {@code @Tool}-методам
 * tool-сервиса); динамические коннекторы (MCP) реализуют напрямую.
 */
public interface ToolProvider {

    Map<String, ConnectorToolSpec> getTools();

    /**
     * Спеки тулов для конкретного экземпляра коннектора. По умолчанию совпадают со
     * статическими {@link #getTools()} — большинство коннекторов не зависят от instance.
     * Динамические коннекторы (например MCP) переопределяют: набор тулов открывается в рантайме
     * per-connectionId, поэтому здесь возвращают список под {@code env.connectionId()} (для MCP —
     * из кэша {@code connection_tools}). Контекст несёт connectionId; расшифровка credentials для листинга
     * не требуется.
     */
    default Map<String, ConnectorToolSpec> getTools(ConnectorEnv env) {
        return getTools();
    }

    Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args);
}
