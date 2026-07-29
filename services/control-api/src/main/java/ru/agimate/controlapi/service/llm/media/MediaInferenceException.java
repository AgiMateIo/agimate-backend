package ru.agimate.controlapi.service.llm.media;

/**
 * A failure of media inference (a model as a tool): an unsupported provider, a refusal or error from
 * the provider, an invalid input file. A domain exception: the media connector maps it to a
 * {@code ConnectorException} — the text is written for a human or the agent, with no secrets.
 */
public class MediaInferenceException extends RuntimeException {

    public MediaInferenceException(String message) {
        super(message);
    }
}
