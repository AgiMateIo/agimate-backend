package ru.agimate.controlapi.controller.mcp.dto;

/** A result with nothing but the discriminator: {@code ping}, and protocol acks in general. */
public record EmptyResult() implements McpResult {

    public static final EmptyResult INSTANCE = new EmptyResult();
}
