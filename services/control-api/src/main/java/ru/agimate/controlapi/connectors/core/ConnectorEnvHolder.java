package ru.agimate.controlapi.connectors.core;

import lombok.experimental.UtilityClass;

/**
 * ThreadLocal-связывание {@link ConnectorEnv} с потоком на время диспатча тулы/таски.
 * Единственный писатель — {@link BaseConnectorHandler} (set/clear в try/finally);
 * tool-сервисы коннекторов только читают через {@link #current()}.
 */
@UtilityClass
public class ConnectorEnvHolder {

    private static final ThreadLocal<ConnectorEnv> ENV = new ThreadLocal<>();

    public static ConnectorEnv current() {
        ConnectorEnv env = ENV.get();
        if (env == null) {
            throw new ConnectorException("No connector env bound to current thread");
        }
        return env;
    }

    static void set(ConnectorEnv env) {
        ENV.set(env);
    }

    static void clear() {
        ENV.remove();
    }
}
