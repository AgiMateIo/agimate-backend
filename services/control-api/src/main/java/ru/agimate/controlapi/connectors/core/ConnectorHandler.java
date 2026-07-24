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
     * Описание для каталога подключений — одна фраза о том, что коннектор даёт пользователю
     * (не как он устроен внутри). Источник истины — код; бутстрап персистит в {@code connectors}.
     */
    default String connectorDescription() {
        return null;
    }

    /**
     * Type-level дескриптор коннектора — только функциональные оси (см. {@link ConnectorTraits}).
     * Источник истины — код; бутстрап персистит в каталог {@code connectors}. Дефолт — internal
     * (backend-исполнение, статические тулы) — подходит и интеграциям вроде telegram;
     * переопределяют только коннекторы с иным видом исполнения или динамическими определениями.
     */
    default ConnectorTraits traits() {
        return ConnectorTraits.internal();
    }
}
