package ru.agimate.controlapi.connectors.core;

import java.util.Map;
import java.util.UUID;

/**
 * Контекст выполнения тулы/таски/webhook-вызова коннектора.
 *
 * @param identity      идентификатор экземпляра коннектора: для integration —
 *                      {@code integration_credentials.id} строкой (как в {@code ToolUseLog});
 *                      для internal — {@code null} (глобально)
 * @param userId        владелец; {@code null} для глобальных internal-тасок
 * @param agentId       агент-инициатор; {@code null} вне tool-use потока (таски, webhooks)
 * @param credentials   расшифрованные credentials; пустая мапа для internal и для webhook
 *                      hot path (валидация/нормализация не требует расшифровки)
 * @param webhookSecret секрет для валидации входящих webhook'ов; {@code null}, если не применимо
 */
public record ConnectorContext(
        String identity,
        UUID userId,
        UUID agentId,
        Map<String, String> credentials,
        String webhookSecret
) {

    public ConnectorContext {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
}
