package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.database.model.ConnectorTraits;

/**
 * Identity-ядро SPI коннектора — общий для internal- и integration-коннекторов.
 *
 * <p>Коннектор — композиция: фасад реализует этот интерфейс плюс нужные capability-интерфейсы
 * ({@link ToolProvider}, {@link TriggerProvider}, {@link JobProvider}, {@link PromptBlockProvider}).
 * Тулы/таски обычно приходят через {@link BaseConnectorHandler} (reflection-диспатч по
 * {@code @Tool}-методам tool-сервиса). Потребители получают capability через
 * {@link ConnectorRegistry#findCapability}/{@link ConnectorRegistry#capability}.
 */
public interface ConnectorHandler {

    String connectorCode();

    default String connectorName() {
        return connectorCode();
    }

    /**
     * Type-level дескриптор коннектора (4 оси, см. {@link ConnectorTraits}). Источник истины —
     * код; бутстрап персистит в каталог {@code connectors}. Дефолт — internal (backend-исполнение,
     * статические тулы, приватный скоуп); коннекторы с иными осями переопределяют.
     */
    default ConnectorTraits traits() {
        return ConnectorTraits.internal();
    }
}
