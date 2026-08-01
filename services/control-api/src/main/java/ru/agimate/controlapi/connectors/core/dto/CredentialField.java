package ru.agimate.controlapi.connectors.core.dto;

/**
 * One field a user fills in when connecting an integration. The declaration is the only place that
 * knows a field's nature, so it carries it: before this record the type was implicit and every client
 * masked every field, and optionality lived inside the label text as the word «(optional)».
 *
 * @param label    human-readable name; the caption of the input
 * @param type     what kind of value it is — drives the control and the masking, nothing else
 * @param required whether an empty value is rejected; the connector still validates on its own
 */
public record CredentialField(String label, Type type, boolean required) {

    /**
     * SECRET is the only one that means «do not show what is typed». It is not a storage instruction:
     * every credential field is encrypted the same way and never returned back, so the distinction is
     * purely about the screen.
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
}
