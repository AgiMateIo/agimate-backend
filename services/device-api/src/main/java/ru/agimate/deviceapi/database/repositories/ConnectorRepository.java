package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Connector;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, Long> {

    Optional<Connector> findByPubId(UUID pubId);

    @Query("SELECT c FROM Connector c WHERE c.pubId = :pubId AND c.deletedAt IS NULL")
    Optional<Connector> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT c FROM Connector c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL AND c.enabled = true")
    List<Connector> findByPubIdNotDeletedAndActive(@Param("userPubId") UUID userPubId);

    @Query("SELECT c FROM Connector c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Connector> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT c FROM Connector c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL AND c.deviceId IS NOT NULL ORDER BY c.createdAt DESC")
    List<Connector> findLinkedByUserPubId(@Param("userPubId") UUID userPubId);

    @Query("SELECT c FROM Connector c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL AND (c.deviceId IS NOT NULL OR c.triggers IS NOT NULL OR c.tools IS NOT NULL) ORDER BY c.createdAt DESC")
    List<Connector> findWithCapabilitiesByUserPubId(@Param("userPubId") UUID userPubId);

    @Query("SELECT c FROM Connector c WHERE c.keyId = :keyId AND c.deletedAt IS NULL AND c.enabled = true")
    Optional<Connector> findActiveKeyByKeyId(@Param("keyId") String keyId);

    @Query("SELECT COUNT(c) FROM Connector c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL")
    long countByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Modifying
    @Query("UPDATE Connector c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(c) > 0 FROM Connector c WHERE c.userPubId = :userPubId AND c.name = :name AND c.deletedAt IS NULL")
    boolean existsByUserPubIdAndName(@Param("userPubId") UUID userPubId, @Param("name") String name);

    @Query("SELECT c FROM Connector c WHERE c.deviceId = :deviceId AND c.userPubId = :userPubId AND c.deletedAt IS NULL")
    Optional<Connector> findByDeviceIdAndUserPubId(@Param("deviceId") String deviceId, @Param("userPubId") UUID userPubId);
}
