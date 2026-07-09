package ru.agimate.controlapi.service.dto;

import java.util.Map;

public interface IToolCall {
    String getId();
    String getConnectorCode();
    String getConnectionId();
    String getName();
    Map<String, Object> getInput();
}
