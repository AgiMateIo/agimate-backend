package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;

import java.util.Map;

/**
 * Capability коннектора: декларация триггеров, которые тип коннектора может публиковать
 * (webhook-нормализация, directed-триггеры фоновых тасок). Динамические триггеры экземпляров
 * (DYNAMIC toolBinding) живут в {@code connection_triggers} и сюда не попадают.
 */
public interface TriggerProvider {

    Map<String, TriggerSpec> getTriggers();
}
