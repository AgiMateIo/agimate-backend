package ru.agimate.controlapi.controller.agent.dto;

/** Статус результата tool_call для HTTP-опроса: зеркало gRPC {@code ToolResultStatus}. */
public enum ToolResultStatus {
    PENDING,
    SUCCESS,
    ERROR
}
