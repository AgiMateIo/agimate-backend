package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.WebhookDelivery;

import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Query("SELECT wd FROM WebhookDelivery wd WHERE wd.webhookUrlId = :webhookUrlId ORDER BY wd.triggeredAt DESC")
    Page<WebhookDelivery> findByWebhookUrlId(
            @Param("webhookUrlId") Long webhookUrlId,
            Pageable pageable
    );

    @Query("SELECT wd FROM WebhookDelivery wd WHERE wd.userPubId = :userPubId ORDER BY wd.triggeredAt DESC")
    Page<WebhookDelivery> findByUserPubId(
            @Param("userPubId") UUID userPubId,
            Pageable pageable
    );

    @Query("SELECT wd FROM WebhookDelivery wd WHERE wd.userPubId = :userPubId AND wd.eventType = :eventType ORDER BY wd.triggeredAt DESC")
    Page<WebhookDelivery> findByUserPubIdAndEventType(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType,
            Pageable pageable
    );

    @Query("SELECT COUNT(wd) FROM WebhookDelivery wd WHERE wd.webhookUrlId = :webhookUrlId AND wd.responseStatusCode >= 200 AND wd.responseStatusCode < 300")
    long countSuccessfulDeliveriesByWebhook(@Param("webhookUrlId") Long webhookUrlId);

    @Query("SELECT COUNT(wd) FROM WebhookDelivery wd WHERE wd.webhookUrlId = :webhookUrlId AND (wd.responseStatusCode < 200 OR wd.responseStatusCode >= 300 OR wd.responseStatusCode IS NULL)")
    long countFailedDeliveriesByWebhook(@Param("webhookUrlId") Long webhookUrlId);
}
