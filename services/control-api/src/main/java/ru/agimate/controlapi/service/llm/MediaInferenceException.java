package ru.agimate.controlapi.service.llm;

/**
 * Сбой медиа-инференса (модель-инструмент): неподдерживаемый провайдер, отказ/ошибка провайдера,
 * невалидный входной файл. Доменное исключение: медиа-коннектор мапит его в
 * {@code ConnectorException} — текст пишется для человека/агента, без секретов.
 */
public class MediaInferenceException extends RuntimeException {

    public MediaInferenceException(String message) {
        super(message);
    }
}
