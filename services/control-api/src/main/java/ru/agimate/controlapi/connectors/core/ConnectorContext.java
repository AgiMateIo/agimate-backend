package ru.agimate.controlapi.connectors.core;

import java.util.Map;
import java.util.UUID;

/**
 * Контекст выполнения тулы/таски/webhook-вызова коннектора.
 *
 * @param connectionId  идентификатор экземпляра коннектора — {@code connections.id} строкой
 *                      (как в {@code ToolCallLog}); {@code null}, если экземпляр не применим
 * @param userId        владелец; {@code null} для глобальных internal-тасок
 * @param agentId       агент-инициатор; {@code null} вне tool-use потока (декларативные таски,
 *                      webhooks); у динамической таски восстанавливается из строки при срабатывании
 * @param channelId     исходный канал вызова: для tool-вызова — канал prompt-сессии (резолвится на
 *                      границе по {@code agentSessionId}); для динамической таски — снимок из строки
 *                      {@code connector_jobs}. {@code null} вне канального контекста. Нужен тулам,
 *                      которым важен исходный канал (например {@code time.schedule} — куда отвечать)
 * @param credentials   расшифрованные credentials; пустая мапа для internal и для webhook
 *                      hot path (валидация/нормализация не требует расшифровки)
 * @param webhookSecret секрет для валидации входящих webhook'ов; {@code null}, если не применимо
 */
public record ConnectorContext(
        String connectionId,
        UUID userId,
        UUID agentId,
        UUID channelId,
        Map<String, String> credentials,
        String webhookSecret
) {

    public ConnectorContext {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
}
