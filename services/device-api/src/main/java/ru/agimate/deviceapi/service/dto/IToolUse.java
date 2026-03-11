package ru.agimate.deviceapi.service.dto;

import java.util.Map;

public interface IToolUse {
    String getId();
    String getConnectorCode();
    String getIdentity();
    String getName();
    Map<String, Object> getInput();
}
