package ru.agimate.controlapi.service.llm;

/**
 * Квота LLM-токенов исчерпана. Доменное исключение (не {@code *StatusException} — те живут
 * на HTTP-границе): gRPC-граница мапит его в {@code RESOURCE_EXHAUSTED}, сообщение доезжает
 * до пользователя ERROR-сообщением рана — текст пишется для человека.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
