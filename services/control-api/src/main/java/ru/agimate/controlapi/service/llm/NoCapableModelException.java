package ru.agimate.controlapi.service.llm;

/**
 * No model was found for the required capability (there is no purpose binding, and the capability
 * match against the user's and the platform's registries came up empty). A domain exception: the
 * media path maps it to a {@code ConnectorException} — the text is written for a human or the agent.
 */
public class NoCapableModelException extends RuntimeException {

    public NoCapableModelException(String message) {
        super(message);
    }
}
