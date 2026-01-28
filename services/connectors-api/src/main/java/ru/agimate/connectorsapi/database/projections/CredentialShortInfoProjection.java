package ru.agimate.connectorsapi.database.projections;

import java.util.UUID;

/**
 * Projection interface for credential short info with connector code.
 */
public interface CredentialShortInfoProjection {

    UUID getPubId();

    String getName();

    String getDescription();

    String getConnectorCode();
}
