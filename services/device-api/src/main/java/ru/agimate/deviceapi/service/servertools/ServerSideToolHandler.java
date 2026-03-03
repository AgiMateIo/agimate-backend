package ru.agimate.deviceapi.service.servertools;

import java.util.Map;
import java.util.UUID;

public interface ServerSideToolHandler {

    String getHandlerCode();

    Map<String, Object> getToolDefinitions();

    Map<String, Object> executeTool(String toolName, Map<String, Object> params,
                                     UUID apiKeyPubId, UUID userPubId);
}
