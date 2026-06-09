package ru.agimate.controlapi.connectors.core;

import lombok.experimental.UtilityClass;

/**
 * ThreadLocal-связывание {@link ConnectorContext} с потоком на время диспатча тулы/таски.
 * Единственный писатель — {@link BaseConnectorHandler} (set/clear в try/finally);
 * tool-сервисы коннекторов только читают через {@link #current()}.
 */
@UtilityClass
public class ConnectorContextHolder {

    private static final ThreadLocal<ConnectorContext> CONTEXT = new ThreadLocal<>();

    public static ConnectorContext current() {
        ConnectorContext context = CONTEXT.get();
        if (context == null) {
            throw new ConnectorException("No connector context bound to current thread");
        }
        return context;
    }

    static void set(ConnectorContext context) {
        CONTEXT.set(context);
    }

    static void clear() {
        CONTEXT.remove();
    }
}
