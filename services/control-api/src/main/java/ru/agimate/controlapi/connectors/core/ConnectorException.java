package ru.agimate.controlapi.connectors.core;

/**
 * Единственное исключение коннекторного слоя: неизвестный коннектор/тула/таска, недоступные
 * credentials, ошибка диспатча. HTTP-граница не должна пропускать его наружу как есть —
 * контроллеры/HTTP-сервисы используют {@code ConnectorRegistry.findHandler(...)} c
 * {@code orElseThrow(*StatusException)} либо переводят сами.
 *
 * <p>Сообщение пишется самим кодом коннекторов и безопасно для показа агенту в tool-result.
 */
public class ConnectorException extends RuntimeException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
