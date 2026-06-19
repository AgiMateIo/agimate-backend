package ru.agimate.controlapi.service.dto;

import java.util.Map;

public interface IToolCall {
    String getId();
    String getConnectorCode();
    String getIdentity();
    String getName();
    Map<String, Object> getInput();
}
