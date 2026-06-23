package ru.agimate.controlapi.abac;

import java.util.UUID;

/**
 * Tool-политики агента изменились (создана/обновлена/удалена). Generic-событие: ABAC-слой не знает
 * про конкретные коннекторы. Подписчики (например {@code MemoryEnablementListener}) сами перечитывают
 * актуальные политики агента и решают, что делать.
 *
 * @param agentId агент, чьи политики изменились
 * @param userId  владелец агента
 */
public record AgentToolPolicyChangedEvent(UUID agentId, UUID userId) {
}
