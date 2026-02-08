package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.WebhookLog;

import java.util.UUID;

@Repository
public interface WebhookLogRepository extends JpaRepository<WebhookLog, Long> {

    @Query("SELECT wl FROM WebhookLog wl WHERE wl.webhookId = :webhookId ORDER BY wl.triggeredAt DESC")
    Page<WebhookLog> findByWebhookId(
            @Param("webhookId") Long webhookId,
            Pageable pageable
    );

    @Query("SELECT wl FROM WebhookLog wl WHERE wl.userPubId = :userPubId ORDER BY wl.triggeredAt DESC")
    Page<WebhookLog> findByUserPubId(
            @Param("userPubId") UUID userPubId,
            Pageable pageable
    );

    @Query("SELECT wl FROM WebhookLog wl WHERE wl.userPubId = :userPubId AND wl.eventType = :eventType ORDER BY wl.triggeredAt DESC")
    Page<WebhookLog> findByUserPubIdAndEventType(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType,
            Pageable pageable
    );

    @Query("SELECT COUNT(wl) FROM WebhookLog wl WHERE wl.webhookId = :webhookId AND wl.responseStatusCode >= 200 AND wl.responseStatusCode < 300")
    long countSuccessfulByWebhook(@Param("webhookId") Long webhookId);

    @Query("SELECT COUNT(wl) FROM WebhookLog wl WHERE wl.webhookId = :webhookId AND (wl.responseStatusCode < 200 OR wl.responseStatusCode >= 300 OR wl.responseStatusCode IS NULL)")
    long countFailedByWebhook(@Param("webhookId") Long webhookId);
}
