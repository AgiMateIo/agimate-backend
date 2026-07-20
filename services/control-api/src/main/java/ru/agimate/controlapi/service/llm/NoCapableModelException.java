package ru.agimate.controlapi.service.llm;

/**
 * Не нашлось модели под требуемую капабилити (purpose-биндинга нет, капабилити-матч по реестрам
 * пользователя и платформы пуст). Доменное исключение: медиа-путь мапит его в
 * {@code ConnectorException} — текст пишется для человека/агента.
 */
public class NoCapableModelException extends RuntimeException {

    public NoCapableModelException(String message) {
        super(message);
    }
}
