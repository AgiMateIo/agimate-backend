package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.deviceapi.database.entities.WebhookDeliveryLog;

import java.util.UUID;

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, Long> {

    @Query("""
            SELECT w FROM WebhookDeliveryLog w
            JOIN w.triggerLogAgent tla
            JOIN tla.triggerLog tl
            WHERE tl.userPubId = :userPubId
            ORDER BY w.deliveredAt DESC
            """)
    Page<WebhookDeliveryLog> findByUserPubId(UUID userPubId, Pageable pageable);

    @Query("""
            SELECT w FROM WebhookDeliveryLog w
            JOIN w.triggerLogAgent tla
            WHERE tla.agent.pubId = :agentPubId
            ORDER BY w.deliveredAt DESC
            """)
    Page<WebhookDeliveryLog> findByAgentPubId(UUID agentPubId, Pageable pageable);
}
