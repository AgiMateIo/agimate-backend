package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.deviceapi.database.entities.TriggerLog;

import java.util.UUID;

public interface TriggerLogRepository extends JpaRepository<TriggerLog, Long> {

    @Query("""
            SELECT t FROM TriggerLog t
            WHERE t.userPubId = :userPubId
            AND (:connectorCode IS NULL OR t.connectorCode = :connectorCode)
            """)
    Page<TriggerLog> findByUserPubIdWithFilters(UUID userPubId, String connectorCode, Pageable pageable);
}
