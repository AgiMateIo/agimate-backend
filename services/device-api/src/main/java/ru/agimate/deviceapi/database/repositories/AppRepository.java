package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface AppRepository extends JpaRepository<App, UUID> {

    @Query("SELECT a FROM App a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<App> findByIdNotDeleted(@Param("id") UUID id);

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

    @Query("SELECT COUNT(a) > 0 FROM App a WHERE a.userId = :userId AND a.name = :name AND a.deletedAt IS NULL")
    boolean existsByUserIdAndName(@Param("userId") UUID userId, @Param("name") String name);

    @Query("SELECT COUNT(a) > 0 FROM App a WHERE a.id = :id AND a.userId = :userId AND a.deletedAt IS NULL")
    boolean existsByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.id = :id AND a.userId = :userId AND a.deletedAt IS NULL")
    Optional<App> findByIdAndUserIdNotDeleted(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT a FROM App a WHERE a.id IN :ids AND a.deletedAt IS NULL")
    List<App> findAllByIdInNotDeleted(@Param("ids") java.util.Collection<UUID> ids);
}
