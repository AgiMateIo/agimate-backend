package ru.agimate.controlapi.database.repositories;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.ConnectorJob;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementation of {@link ConnectorJobRepositoryCustom}. Spring Data JPA picks it up by the naming
 * convention ({@code <RepositoryName>Impl}) and merges it into {@link ConnectorJobRepository}.
 */
public class ConnectorJobRepositoryImpl implements ConnectorJobRepositoryCustom {

    private static final String CLAIM_SQL = """
            UPDATE connector_jobs
            SET status = 'RUNNING',
                lease_until = cast(:now as timestamp) + (timeout_seconds * interval '1 second'),
                last_started_at = :now
            WHERE id IN (
                SELECT id FROM connector_jobs
                 WHERE paused_at IS NULL
                   AND ((status = 'PENDING' AND next_run_at <= :now)
                    OR (status = 'RUNNING' AND lease_until <= :now))
                 ORDER BY next_run_at NULLS FIRST
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    @Transactional
    public List<ConnectorJob> claimReady(LocalDateTime now, int batchSize) {
        Query query = entityManager.createNativeQuery(CLAIM_SQL, ConnectorJob.class);
        query.setParameter("now", now);
        query.setParameter("batchSize", batchSize);
        return (List<ConnectorJob>) query.getResultList();
    }
}
