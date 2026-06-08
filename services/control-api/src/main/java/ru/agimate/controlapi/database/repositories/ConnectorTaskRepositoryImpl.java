package ru.agimate.controlapi.database.repositories;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.ConnectorTask;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Реализация {@link ConnectorTaskRepositoryCustom}. Spring Data JPA подцепляет её по конвенции
 * имени ({@code <RepositoryName>Impl}) и сливает с интерфейсом {@link ConnectorTaskRepository}.
 */
public class ConnectorTaskRepositoryImpl implements ConnectorTaskRepositoryCustom {

    private static final String CLAIM_SQL = """
            UPDATE connector_tasks
            SET status = 'RUNNING',
                lease_until = :leaseUntil,
                last_started_at = :now
            WHERE id IN (
                SELECT id FROM connector_tasks
                 WHERE enabled = true
                   AND (
                        (status = 'PENDING' AND next_run_at <= :now)
                     OR (status = 'RUNNING' AND lease_until <= :now)
                   )
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
    public List<ConnectorTask> claimReady(LocalDateTime now, Duration leaseDuration, int batchSize) {
        Query query = entityManager.createNativeQuery(CLAIM_SQL, ConnectorTask.class);
        query.setParameter("now", now);
        query.setParameter("leaseUntil", now.plus(leaseDuration));
        query.setParameter("batchSize", batchSize);
        return (List<ConnectorTask>) query.getResultList();
    }
}
