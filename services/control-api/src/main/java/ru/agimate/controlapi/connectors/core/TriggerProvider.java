package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;

import java.util.Map;

/**
 * A connector capability: declaration of the triggers a connector type can publish (webhook
 * normalisation, directed triggers of background jobs). Dynamic triggers of instances (DYNAMIC
 * definitionBinding) live in {@code connection_triggers} and never appear here.
 */
public interface TriggerProvider {

    Map<String, TriggerSpec> getTriggers();
}
