package ru.agimate.deviceapi.connectors.integrations.events;

import java.util.UUID;

public record IntegrationDeletedEvent(UUID integrationId, String connectorCode) {
}
