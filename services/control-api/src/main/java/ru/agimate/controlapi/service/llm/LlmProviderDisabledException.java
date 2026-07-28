package ru.agimate.controlapi.service.llm;

/**
 * The LLM binding's provider is disabled. A domain exception (not a {@code *StatusException} — those
 * live at the HTTP boundary): the gRPC boundary maps it to {@code FAILED_PRECONDITION}, and the media
 * path to a {@code ConnectorException}.
 */
public class LlmProviderDisabledException extends RuntimeException {

    public LlmProviderDisabledException(String message) {
        super(message);
    }
}
