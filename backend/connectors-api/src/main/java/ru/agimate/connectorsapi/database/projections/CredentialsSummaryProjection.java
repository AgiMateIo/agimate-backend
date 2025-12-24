package ru.agimate.connectorsapi.database.projections;

import java.time.LocalDateTime;

/**
 * Projection interface for aggregated credentials summary by connector.
 * Used to optimize the getCredentialsSummary query by reducing N+1 queries to a single query.
 */
public interface CredentialsSummaryProjection {

    String getConnectorCode();

    String getConnectorName();

    Long getCredentialCount();

    LocalDateTime getLastAddedAt();

    LocalDateTime getLastUsedAt();
}
