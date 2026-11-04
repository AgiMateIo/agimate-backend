package ru.agimate.controlapi.database.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.App;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppRepository extends JpaRepository<App, UUID> {

    @Query("SELECT a FROM App a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<App> findByIdNotDeleted(@Param("id") UUID id);

    /**
     * The link path takes the row for update: two starts of the same app must not both decide that the
     * origin skill is missing. The lock serialises them in the database, so the second sees the first
     * one's skill instead of racing the unique origin index (a lost race would mark the whole
     * transaction rollback-only, and the caught exception would not save it).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM App a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<App> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT a FROM App a WHERE a.userId = :userId AND a.deletedAt IS NULL AND a.enabled = true")
    List<App> findByUserIdNotDeletedAndActive(@Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.userId = :userId AND a.deletedAt IS NULL ORDER BY a.createdAt DESC")
    List<App> findByUserIdNotDeleted(@Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.userId = :userId AND a.deletedAt IS NULL ORDER BY a.createdAt DESC")
    Page<App> findByUserIdNotDeleted(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT a FROM App a WHERE a.userId = :userId AND a.deletedAt IS NULL AND (a.deviceId IS NOT NULL OR a.triggers IS NOT NULL OR a.tools IS NOT NULL) ORDER BY a.createdAt DESC")
    List<App> findWithCapabilitiesByUserId(@Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.keyId = :keyId AND a.deletedAt IS NULL AND a.enabled = true")
    Optional<App> findActiveKeyByKeyId(@Param("keyId") String keyId);

    @Query("SELECT COUNT(a) FROM App a WHERE a.userId = :userId AND a.deletedAt IS NULL")
    long countByUserIdNotDeleted(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE App a SET a.deletedAt = :now WHERE a.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(a) > 0 FROM App a WHERE a.id = :id AND a.userId = :userId AND a.deletedAt IS NULL")
    boolean existsByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.id = :id AND a.userId = :userId AND a.deletedAt IS NULL")
    Optional<App> findByIdAndUserIdNotDeleted(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.id IN :ids AND a.deletedAt IS NULL")
    List<App> findAllByIdInNotDeleted(@Param("ids") java.util.Collection<UUID> ids);
}
