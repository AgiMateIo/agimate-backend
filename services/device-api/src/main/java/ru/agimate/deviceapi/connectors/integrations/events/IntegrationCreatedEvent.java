package ru.agimate.deviceapi.connectors.integrations.events;

import java.util.UUID;

public record IntegrationCreatedEvent(UUID integrationPubId, String connectorCode) {
}
