package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.controlapi.database.entities.WebhookDeliveryLog;

import java.util.UUID;

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, UUID> {

    @Query("""
            SELECT w FROM WebhookDeliveryLog w
            JOIN w.triggerLogAgent tla
            JOIN tla.triggerLog tl
            WHERE tl.userId = :userId
            ORDER BY w.deliveredAt DESC
            """)
    Page<WebhookDeliveryLog> findByUserId(UUID userId, Pageable pageable);

    @Query("""
            SELECT w FROM WebhookDeliveryLog w
            JOIN w.triggerLogAgent tla
            WHERE tla.agent.id = :agentId
            ORDER BY w.deliveredAt DESC
            """)
    Page<WebhookDeliveryLog> findByAgentId(UUID agentId, Pageable pageable);
}
