package ru.agimate.mobileapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceAuthKeyRepository extends JpaRepository<DeviceAuthKey, Long> {

    Optional<DeviceAuthKey> findByPubId(UUID pubId);

    @Query("SELECT d FROM DeviceAuthKey d WHERE d.pubId = :pubId AND d.deletedAt IS NULL")
    Optional<DeviceAuthKey> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT d FROM DeviceAuthKey d WHERE d.userPubId = :userPubId AND d.deletedAt IS NULL ORDER BY d.createdAt DESC")
    List<DeviceAuthKey> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT d FROM DeviceAuthKey d WHERE d.keyId = :keyId AND d.deletedAt IS NULL AND d.enabled = true")
    Optional<DeviceAuthKey> findActiveKeyByKeyId(@Param("keyId") String keyId);

    @Query("SELECT COUNT(d) FROM DeviceAuthKey d WHERE d.userPubId = :userPubId AND d.deletedAt IS NULL")
    long countByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Modifying
    @Query("UPDATE DeviceAuthKey d SET d.deletedAt = :now WHERE d.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(d) > 0 FROM DeviceAuthKey d WHERE d.userPubId = :userPubId AND d.name = :name AND d.deletedAt IS NULL")
    boolean existsByUserPubIdAndName(@Param("userPubId") UUID userPubId, @Param("name") String name);
}
