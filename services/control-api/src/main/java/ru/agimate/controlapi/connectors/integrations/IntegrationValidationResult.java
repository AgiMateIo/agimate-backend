package ru.agimate.controlapi.connectors.integrations;

public record IntegrationValidationResult(
        boolean valid,
        String identifier,
        String displayName,
        String errorField,
        String errorMessage
) {
    public static IntegrationValidationResult success(String identifier, String displayName) {
        return new IntegrationValidationResult(true, identifier, displayName, null, null);
    }

    public static IntegrationValidationResult failure(String field, String errorMessage) {
        return new IntegrationValidationResult(false, null, null, field, errorMessage);
    }
}
