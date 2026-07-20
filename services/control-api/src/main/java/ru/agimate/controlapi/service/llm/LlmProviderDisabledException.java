package ru.agimate.controlapi.service.llm;

/**
 * Провайдер LLM-биндинга выключен. Доменное исключение (не {@code *StatusException} — те живут
 * на HTTP-границе): gRPC-граница мапит его в {@code FAILED_PRECONDITION}, медиа-путь — в
 * {@code ConnectorException}.
 */
public class LlmProviderDisabledException extends RuntimeException {

    public LlmProviderDisabledException(String message) {
        super(message);
    }
}
