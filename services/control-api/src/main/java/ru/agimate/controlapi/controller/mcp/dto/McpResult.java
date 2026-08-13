package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A result of the {@code 2026-07-28} revision: every result carries {@code resultType} — the
 * discriminator a client reads before the body. This server always answers {@code "complete"};
 * the tasks extension is what puts {@code "task"} on the same field, in lieu of a
 * {@link ToolCallResult}.
 *
 * <p>{@code @JsonProperty} is load-bearing: {@code resultType()} is not a bean-convention getter,
 * and without the annotation Jackson would leave the field out of the JSON.
 */
public sealed interface McpResult
        permits EmptyResult, InitializeResult, DiscoverResult, ToolsListResult, ToolCallResult {

    @JsonProperty("resultType")
    default String resultType() {
        return "complete";
    }
}
