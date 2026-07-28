package ru.agimate.controlapi.service.llm;

/**
 * The LLM token quota is exhausted. A domain exception (not a {@code *StatusException} — those live
 * at the HTTP boundary): the gRPC boundary maps it to {@code RESOURCE_EXHAUSTED}, and the message
 * reaches the user as the run's ERROR message — so the text is written for a human.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
