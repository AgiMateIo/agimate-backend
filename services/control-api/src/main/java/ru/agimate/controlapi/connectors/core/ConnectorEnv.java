package ru.agimate.controlapi.connectors.core;

import java.util.Map;
import java.util.UUID;

/**
 * Среда одного обращения к SPI коннектора (тула/таска/prompt-блоки/листинг/webhook):
 * адресация экземпляра, идентичность вызывающего и секреты.
 *
 * @param connectionId  идентификатор экземпляра коннектора — {@code connections.id} строкой
 *                      (как в {@code ToolCallLog}); {@code null}, если экземпляр не применим
 * @param userId        владелец; {@code null} для глобальных internal-тасок
 * @param agentId       агент-инициатор; {@code null} вне tool-use потока (декларативные таски,
 *                      webhooks); у динамической таски восстанавливается из строки при срабатывании
 * @param runId         ран-инициатор вызова ({@code agent_runs.id}); {@code null} вне tool-use
 *                      потока рана (webhooks, listing, джобы, lifecycle). Нужен учёту расхода
 *                      «модель как инструмент» (media) для привязки usage к рану
 * @param channelId     исходный канал вызова: для tool-вызова — канал prompt-сессии (резолвится на
 *                      границе по {@code agentSessionId}); для динамической таски — снимок из строки
 *                      {@code connector_jobs}. {@code null} вне канального контекста. Нужен тулам,
 *                      которым важен исходный канал (например {@code time.schedule} — куда отвечать)
 * @param sessionId     prompt-сессия вызова ({@code channel_sessions.id}); {@code null} вне
 *                      канального tool-use потока. Нужен тулам, адресующим конкретную живую сессию
 *                      (IDE-коннектор — ключ {@code AcpSessionRegistry})
 * @param credentials   расшифрованные credentials; пустая мапа для internal и для webhook
 *                      hot path (валидация/нормализация не требует расшифровки)
 * @param webhookSecret секрет для валидации входящих webhook'ов; {@code null}, если не применимо
 */
public record ConnectorEnv(
        String connectionId,
        UUID userId,
        UUID agentId,
        UUID runId,
        UUID channelId,
        UUID sessionId,
        Map<String, String> credentials,
        String webhookSecret
) {

    public ConnectorEnv {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
}
