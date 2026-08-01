package ru.agimate.controlapi.connectors.core.dto;

import java.util.Map;

/**
 * Outcome of checking an integration's credentials. Three states, not two: «the server is alive, the
 * input is fine, but the user has to authorise» is not an input error and does not fit the
 * valid/invalid fork.
 *
 * @param derivedCredentials what the check itself discovered and the connection's secret needs —
 *                           OAuth issuer, endpoints, canonical resource, chosen scope. Merged on top
 *                           of what the user typed; never contains user input back
 */
public record IntegrationValidationResult(
        Outcome outcome,
        String identifier,
        String displayName,
        String errorField,
        String errorMessage,
        Map<String, String> derivedCredentials
) {

    public enum Outcome { VALID, AUTHORIZATION_REQUIRED, INVALID }

    public IntegrationValidationResult {
        derivedCredentials = derivedCredentials == null ? Map.of() : Map.copyOf(derivedCredentials);
    }

    public static IntegrationValidationResult success(String identifier, String displayName) {
        return new IntegrationValidationResult(Outcome.VALID, identifier, displayName, null, null, Map.of());
    }

    /** The credentials are good, but the connection cannot work until an OAuth grant is obtained. */
    public static IntegrationValidationResult authorizationRequired(
            String identifier, String displayName, Map<String, String> derivedCredentials) {
        return new IntegrationValidationResult(
                Outcome.AUTHORIZATION_REQUIRED, identifier, displayName, null, null, derivedCredentials);
    }

    public static IntegrationValidationResult failure(String field, String errorMessage) {
        return new IntegrationValidationResult(Outcome.INVALID, null, null, field, errorMessage, Map.of());
    }

    /** Whether the platform accepted the credentials — true for both «ready» and «needs authorisation». */
    public boolean valid() {
        return outcome != Outcome.INVALID;
    }

    public boolean authorizationRequired() {
        return outcome == Outcome.AUTHORIZATION_REQUIRED;
    }
}
