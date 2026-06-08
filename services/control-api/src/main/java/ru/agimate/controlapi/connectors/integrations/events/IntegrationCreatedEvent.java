package ru.agimate.controlapi.connectors.integrations.events;

import java.util.UUID;

public record IntegrationCreatedEvent(UUID integrationId, String connectorCode) {
}
