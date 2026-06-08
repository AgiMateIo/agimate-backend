package ru.agimate.controlapi.connectors.integrations.events;

import java.util.UUID;

public record IntegrationDeletedEvent(UUID integrationId, String connectorCode) {
}
