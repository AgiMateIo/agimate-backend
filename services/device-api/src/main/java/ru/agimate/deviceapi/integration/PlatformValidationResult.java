package ru.agimate.deviceapi.integration;

public record PlatformValidationResult(
        boolean valid,
        String identifier,
        String displayName,
        String errorField,
        String errorMessage
) {
    public static PlatformValidationResult success(String identifier, String displayName) {
        return new PlatformValidationResult(true, identifier, displayName, null, null);
    }

    public static PlatformValidationResult failure(String field, String errorMessage) {
        return new PlatformValidationResult(false, null, null, field, errorMessage);
    }
}
