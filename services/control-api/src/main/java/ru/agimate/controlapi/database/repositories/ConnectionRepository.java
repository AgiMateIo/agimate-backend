package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Connection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectionRepository extends JpaRepository<Connection, UUID> {

    @Query("SELECT c FROM Connection c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Connection> findByIdNotDeleted(@Param("id") UUID id);

    @Query("SELECT c FROM Connection c WHERE c.id = :id AND c.userId = :userId AND c.deletedAt IS NULL")
    Optional<Connection> findByIdAndUserIdNotDeleted(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM Connection c WHERE c.userId = :userId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Connection> findByUserIdNotDeleted(@Param("userId") UUID userId);

    @Query("""
            SELECT c FROM Connection c
            WHERE c.userId = :userId
              AND c.connectorCode = :connectorCode
              AND c.deletedAt IS NULL
            ORDER BY c.createdAt DESC
            """)
    List<Connection> findByUserIdAndConnectorCodeNotDeleted(
            @Param("userId") UUID userId,
            @Param("connectorCode") String connectorCode);

    @Query("""
            SELECT c FROM Connection c
            WHERE c.connectorCode = :connectorCode
              AND c.enabled = true
              AND c.deletedAt IS NULL
            """)
    List<Connection> findActiveByConnectorCode(@Param("connectorCode") String connectorCode);

    Optional<Connection> findByAppIdAndDeletedAtIsNull(UUID appId);

    boolean existsByConnectorCodeAndUserIdAndSubCodeAndDeletedAtIsNull(
            String connectorCode, UUID userId, String subCode);

    @Modifying
    @Query("UPDATE Connection c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE Connection c SET c.lastUsedAt = :now WHERE c.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
