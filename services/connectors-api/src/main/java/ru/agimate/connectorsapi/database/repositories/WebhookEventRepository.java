package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.WebhookEvent;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    List<WebhookEvent> findByWebhookId(Long webhookId);

    @Query("SELECT e FROM WebhookEvent e WHERE e.userPubId = :userPubId AND e.eventType = :eventType")
    List<WebhookEvent> findByUserPubIdAndEventType(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType
    );

    void deleteByWebhookId(Long webhookId);
}
