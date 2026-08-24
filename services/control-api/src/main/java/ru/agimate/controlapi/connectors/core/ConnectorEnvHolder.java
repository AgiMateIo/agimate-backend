package ru.agimate.controlapi.connectors.core;

import lombok.experimental.UtilityClass;

/**
 * ThreadLocal binding of a {@link ConnectorEnv} to the thread for the duration of a tool or job
 * dispatch. The only writer is {@link BaseConnectorHandler} (set/clear in a try/finally); connectors'
 * tool services only read, through {@link #current()}.
 */
@UtilityClass
public class ConnectorEnvHolder {

    private static final ThreadLocal<ConnectorEnv> ENV = new ThreadLocal<>();

    /** {@code null} — nothing is bound: the caller is outside a dispatch, which is not always an error. */
    public static ConnectorEnv currentOrNull() {
        return ENV.get();
    }

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
