package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

/**
 * Тул в составе контекста рана: спека + адресация экземпляра для маршрутизации ExecuteTool
 * и построения LLM-имени ({@code {namespace}.{name}}) на воркере.
 */
public record RunTool(
        ConnectorToolSpec spec,
        String connectorCode,
        String connectionId,
        String namespace
) {
}
