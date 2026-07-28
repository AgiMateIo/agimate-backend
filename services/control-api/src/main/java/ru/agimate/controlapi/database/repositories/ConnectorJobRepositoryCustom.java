package ru.agimate.controlapi.database.repositories;

import ru.agimate.controlapi.database.entities.ConnectorJob;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Custom methods of {@link ConnectorJobRepository} that require native SQL — Spring Data JPA cannot
 * express {@code UPDATE … FROM (… FOR UPDATE SKIP LOCKED) RETURNING *} through {@code @Query}.
 */
public interface ConnectorJobRepositoryCustom {

    /**
     * Atomically claims up to {@code batchSize} rows that are ready to run:
     * <ul>
     *   <li>{@code status=PENDING AND next_run_at <= now} — the normal pickup;</li>
     *   <li>or {@code status=RUNNING AND lease_until <= now} — crash recovery of a stuck row.</li>
     * </ul>
     *
     * <p>Underneath, {@code SELECT … FOR UPDATE SKIP LOCKED} divides the work correctly between
     * several nodes without locking. The returned rows are immediately moved to
     * {@code status=RUNNING} with a lease until {@code now + timeout_seconds} (per row) — no separate
     * commit is needed on the caller's side.
     */
    List<ConnectorJob> claimReady(LocalDateTime now, int batchSize);
}
