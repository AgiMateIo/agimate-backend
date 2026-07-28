package ru.agimate.controlapi.controller.agent.dto;

/** Status of a tool_call result for HTTP polling: the mirror of the gRPC {@code ToolResultStatus}. */
public enum ToolResultStatus {
    PENDING,
    SUCCESS,
    ERROR
}
