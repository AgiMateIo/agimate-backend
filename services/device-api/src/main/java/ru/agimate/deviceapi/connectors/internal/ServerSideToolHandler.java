package ru.agimate.deviceapi.connectors.internal;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.Map;
import java.util.UUID;

public interface ServerSideToolHandler {

    String getConnectorCode();

    Map<String, ToolSpecification> getToolDefinitions();

    Map<String, Object> executeTool(String toolName, Map<String, Object> params,
                                     UUID agentId, UUID userPubId);
}
