package ru.agimate.deviceapi.service;

import java.util.Map;

public interface IToolUse {
    String getId();
    String getName();
    Map<String, Object> getParams();
}
