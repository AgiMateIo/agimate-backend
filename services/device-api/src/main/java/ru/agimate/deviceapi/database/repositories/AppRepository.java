package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.App;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppRepository extends JpaRepository<App, Long> {

    Optional<App> findByPubId(UUID pubId);

    @Query("SELECT a FROM App a WHERE a.pubId = :pubId AND a.deletedAt IS NULL")
    Optional<App> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT a FROM App a WHERE a.userPubId = :userPubId AND a.deletedAt IS NULL AND a.enabled = true")
    List<App> findByPubIdNotDeletedAndActive(@Param("userPubId") UUID userPubId);

    @Query("SELECT a FROM App a WHERE a.userPubId = :userPubId AND a.deletedAt IS NULL ORDER BY a.createdAt DESC")
    List<App> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT a FROM App a WHERE a.userPubId = :userPubId AND a.deletedAt IS NULL AND a.deviceId IS NOT NULL ORDER BY a.createdAt DESC")
    List<App> findLinkedByUserPubId(@Param("userPubId") UUID userPubId);

    @Query("SELECT a FROM App a WHERE a.keyId = :keyId AND a.deletedAt IS NULL AND a.enabled = true")
    Optional<App> findActiveKeyByKeyId(@Param("keyId") String keyId);

    @Query("SELECT COUNT(a) FROM App a WHERE a.userPubId = :userPubId AND a.deletedAt IS NULL")
    long countByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Modifying
    @Query("UPDATE App a SET a.deletedAt = :now WHERE a.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(a) > 0 FROM App a WHERE a.userPubId = :userPubId AND a.name = :name AND a.deletedAt IS NULL")
    boolean existsByUserPubIdAndName(@Param("userPubId") UUID userPubId, @Param("name") String name);

    @Query("SELECT a FROM App a WHERE a.deviceId = :deviceId AND a.userPubId = :userPubId AND a.deletedAt IS NULL")
    Optional<App> findByDeviceIdAndUserPubId(@Param("deviceId") String deviceId, @Param("userPubId") UUID userPubId);
}
