package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.WebhookUrl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookUrlRepository extends JpaRepository<WebhookUrl, Long> {

    @Query("SELECT w FROM WebhookUrl w WHERE w.pubId = :pubId AND w.userPubId = :userPubId AND w.deletedAt IS NULL")
    Optional<WebhookUrl> findByPubIdAndUserPubIdNotDeleted(
            @Param("pubId") UUID pubId,
            @Param("userPubId") UUID userPubId
    );

    @Query("SELECT w FROM WebhookUrl w WHERE w.userPubId = :userPubId AND w.deletedAt IS NULL ORDER BY w.createdAt DESC")
    List<WebhookUrl> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("""
        SELECT DISTINCT w 
        FROM WebhookUrl w 
        JOIN w.events e 
            WHERE w.userPubId = :userPubId 
                AND e.eventType = :eventType 
                AND w.deletedAt IS NULL 
                AND w.enabled = true 
        ORDER BY w.createdAt DESC
    """)
    List<WebhookUrl> findActiveByUserPubIdAndEventType(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType
    );

    @Query("SELECT w FROM WebhookUrl w WHERE w.userPubId = :userPubId AND w.url = :url AND w.deletedAt IS NULL")
    Optional<WebhookUrl> findByUserPubIdAndUrl(
            @Param("userPubId") UUID userPubId,
            @Param("url") String url
    );

    @Modifying
    @Query("UPDATE WebhookUrl w SET w.deletedAt = :now WHERE w.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);
}
