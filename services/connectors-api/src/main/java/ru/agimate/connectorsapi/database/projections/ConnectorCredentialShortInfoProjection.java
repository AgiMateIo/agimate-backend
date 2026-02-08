package ru.agimate.connectorsapi.database.projections;

import java.util.UUID;

/**
 * Projection interface for connector credential short info with connector code.
 */
public interface ConnectorCredentialShortInfoProjection {

    UUID getPubId();

    String getName();

    String getDescription();

    String getConnectorCode();
}
