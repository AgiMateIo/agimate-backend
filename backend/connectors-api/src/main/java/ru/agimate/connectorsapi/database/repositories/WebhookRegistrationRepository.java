package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.WebhookRegistration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookRegistrationRepository extends JpaRepository<WebhookRegistration, Long> {

    @Query("SELECT w FROM WebhookRegistration w WHERE w.pubId = :pubId AND w.userPubId = :userPubId AND w.deletedAt IS NULL")
    Optional<WebhookRegistration> findByPubIdAndUserPubIdNotDeleted(
            @Param("pubId") UUID pubId,
            @Param("userPubId") UUID userPubId
    );

    @Query("SELECT w FROM WebhookRegistration w WHERE w.userPubId = :userPubId AND w.deletedAt IS NULL ORDER BY w.createdAt DESC")
    List<WebhookRegistration> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT w FROM WebhookRegistration w WHERE w.userPubId = :userPubId AND w.eventType = :eventType AND w.deletedAt IS NULL ORDER BY w.createdAt DESC")
    List<WebhookRegistration> findByUserPubIdAndEventTypeNotDeleted(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType
    );

    @Query("SELECT w FROM WebhookRegistration w WHERE w.userPubId = :userPubId AND w.eventType = :eventType AND w.url = :url AND w.deletedAt IS NULL")
    Optional<WebhookRegistration> findDuplicate(
            @Param("userPubId") UUID userPubId,
            @Param("eventType") String eventType,
            @Param("url") String url
    );

    @Modifying
    @Query("UPDATE WebhookRegistration w SET w.deletedAt = :now WHERE w.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);
}
