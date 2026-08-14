package ru.agimate.agentworker.agent.model;

/**
 * A tool definition handed to the LLM. {@code name} is the sanitized identifier the model sees
 * (OpenAI's function-name grammar forbids dots); reverse mapping back to the backend
 * {@code (connector_code, name, connectionId)} is {@link ru.agimate.agentworker.agent.ToolRegistry}'s
 * responsibility.
 * {@code parametersJsonSchema} is the raw JSON Schema string for the tool's input.
 */
public record ToolDef(String name, String description, String parametersJsonSchema) {
}
