package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.WebhookUrlEvent;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookUrlEventRepository extends JpaRepository<WebhookUrlEvent, Long> {

    List<WebhookUrlEvent> findByWebhookUrlId(Long webhookUrlId);

    @Query("SELECT e FROM WebhookUrlEvent e WHERE e.userPubId = :userPubId AND e.eventType = :eventType")
    List<WebhookUrlEvent> findByUserPubIdAndEventType(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType
    );

    void deleteByWebhookUrlId(Long webhookUrlId);
}
