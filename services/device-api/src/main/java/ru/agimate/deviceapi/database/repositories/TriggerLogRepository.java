package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.deviceapi.database.entities.TriggerLog;

import java.util.List;
import java.util.UUID;

public interface TriggerLogRepository extends JpaRepository<TriggerLog, Long> {

    @Query("""
            SELECT t FROM TriggerLog t JOIN FETCH t.deviceAuthKey
            WHERE t.userPubId = :userPubId
            AND (:linkedDeviceId IS NULL OR t.linkedDeviceId = :linkedDeviceId)
            AND (:deviceAuthKeyPubId IS NULL OR t.deviceAuthKey.pubId = :deviceAuthKeyPubId)
            ORDER BY t.createdAt DESC
            """)
    List<TriggerLog> findByUserPubIdWithFilters(UUID userPubId, String linkedDeviceId, UUID deviceAuthKeyPubId);
}
