package ru.agimate.controlapi.connectors.core;

import java.util.Map;
import java.util.UUID;

/**
 * Контекст выполнения тулы/таски/webhook-вызова коннектора.
 *
 * @param identity      идентификатор экземпляра коннектора: для integration —
 *                      {@code integration_credentials.id} строкой (как в {@code ToolCallLog});
 *                      для internal — identity из tool-вызова (например pubId доски),
 *                      {@code null} если экземпляр не применим
 * @param userId        владелец; {@code null} для глобальных internal-тасок
 * @param agentId       агент-инициатор; {@code null} вне tool-use потока (декларативные таски,
 *                      webhooks); у динамической таски восстанавливается из строки при срабатывании
 * @param agentSessionId сессия prompt-канала, из которого пришёл tool-вызов (id {@code ChannelSession}
 *                      строкой); {@code null} вне канального tool-use потока. Нужна тулам, которым
 *                      важен исходный канал (например {@code time.schedule} снимает с неё канал ответа)
 * @param credentials   расшифрованные credentials; пустая мапа для internal и для webhook
 *                      hot path (валидация/нормализация не требует расшифровки)
 * @param webhookSecret секрет для валидации входящих webhook'ов; {@code null}, если не применимо
 */
public record ConnectorContext(
        String identity,
        UUID userId,
        UUID agentId,
        String agentSessionId,
        Map<String, String> credentials,
        String webhookSecret
) {

    public ConnectorContext {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
}
