package ru.agimate.controlapi.connectors.core.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One field a user fills in when connecting an integration. The declaration is the only place that
 * knows a field's nature, so it carries it: before this record the type was implicit and every client
 * masked every field, and optionality lived inside the label text as the word «(optional)».
 *
 * @param label    human-readable name; the caption of the input
 * @param type     what kind of value it is — drives the control, the masking and where the value is kept
 * @param required whether an empty value is rejected; the connector still validates on its own
 */
public record CredentialField(String label, Type type, boolean required) {

    /**
     * SECRET is kept in the connection's encrypted secret, masked on input and never returned. Every
     * other type is kept openly in {@code connections.params}: it is returned in the connection's
     * response and matched against the params a skill declares. Declaring a field non-SECRET is
     * agreeing to exactly that — a connector unsure what a field may hold declares it SECRET.
     */
    public enum Type {
        TEXT, SECRET, URL, JSON
    }

    public static CredentialField required(String label, Type type) {
        return new CredentialField(label, type, true);
    }

    public static CredentialField optional(String label, Type type) {
        return new CredentialField(label, type, false);
    }

    public boolean secret() {
        return type == Type.SECRET;
    }

    /**
     * The value as it is stored and compared. A skill's params meet a connection's by string equality,
     * so both sides go through this before they are written.
     */
    public String normalize(String value) {
        return value != null && (type == Type.URL || type == Type.TEXT) ? value.trim() : value;
    }

    /** {@link #normalize} for every declared key; an undeclared key passes as is. */
    public static Map<String, String> normalize(Map<String, CredentialField> fields, Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((code, value) -> {
            CredentialField field = fields.get(code);
            normalized.put(code, field == null ? value : field.normalize(value));
        });
        return normalized;
    }
}
