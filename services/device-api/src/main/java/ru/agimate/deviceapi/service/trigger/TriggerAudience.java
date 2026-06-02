package ru.agimate.deviceapi.service.trigger;

import java.util.List;
import java.util.UUID;

// TODO: пересмотреть это решение с Audience и сделать его универасальным для всех типов триггеров и сообщений для всех типов агентов.
public record TriggerAudience(
        UUID actorAgentId,
        List<UUID> targetAgentIds
) {
}
