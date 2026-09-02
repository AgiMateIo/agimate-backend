package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.WebhookDeliveryLog;

import java.time.LocalDateTime;
import java.util.UUID;

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, UUID> {

    /**
     * The deliveries of the user's webhook agents, newest first, optionally narrowed to one agent
     * and to a {@code deliveredAt} window. {@code agentId}/{@code since}/{@code until} are optional
     * — null means "no filter", the connector's convention for every listing.
     */
    @Query("""
            SELECT w FROM WebhookDeliveryLog w
            JOIN w.agentRun tla
            JOIN tla.triggerLog tl
            WHERE tl.userId = :userId
            AND (:agentId IS NULL OR tla.agent.id = :agentId)
            AND (:since IS NULL OR w.deliveredAt >= :since)
            AND (:until IS NULL OR w.deliveredAt <= :until)
            ORDER BY w.deliveredAt DESC
            """)
    Page<WebhookDeliveryLog> findWithFilters(@Param("userId") UUID userId,
                                             @Param("agentId") UUID agentId,
                                             @Param("since") LocalDateTime since,
                                             @Param("until") LocalDateTime until,
                                             Pageable pageable);
}
