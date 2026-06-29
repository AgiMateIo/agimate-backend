package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.IdentityScope;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectionRepository extends JpaRepository<Connection, UUID> {

    @Query("SELECT c FROM Connection c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Connection> findByIdNotDeleted(@Param("id") UUID id);

    @Query("SELECT c FROM Connection c WHERE c.id IN :ids AND c.deletedAt IS NULL")
    List<Connection> findByIdInNotDeleted(@Param("ids") Collection<UUID> ids);

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

    /** Connection пользователя с опциональными фильтрами по реальным полям (null = не фильтровать). */
    @Query("""
            SELECT c FROM Connection c
            WHERE c.userId = :userId
              AND c.deletedAt IS NULL
              AND (:connectorCode IS NULL OR c.connectorCode = :connectorCode)
              AND (:scope IS NULL OR c.identityScope = :scope)
              AND (:enabled IS NULL OR c.enabled = :enabled)
            ORDER BY c.connectorCode, c.createdAt DESC
            """)
    List<Connection> findByUserIdFiltered(
            @Param("userId") UUID userId,
            @Param("connectorCode") String connectorCode,
            @Param("scope") IdentityScope scope,
            @Param("enabled") Boolean enabled);

    @Query("""
            SELECT c FROM Connection c
            WHERE c.connectorCode = :connectorCode
              AND c.enabled = true
              AND c.deletedAt IS NULL
            """)
    List<Connection> findActiveByConnectorCode(@Param("connectorCode") String connectorCode);

    Optional<Connection> findByAppIdAndDeletedAtIsNull(UUID appId);

    /** Активные экземпляры, привязанные к агенту через {@code agent_connections} (гейт доступности). */
    @Query("""
            SELECT c FROM Connection c, AgentConnection ac
            WHERE ac.connectionId = c.id
              AND ac.agentId = :agentId
              AND ac.deletedAt IS NULL
              AND c.deletedAt IS NULL
              AND c.enabled = true
            ORDER BY c.connectorCode, c.createdAt
            """)
    List<Connection> findActiveBoundToAgent(@Param("agentId") UUID agentId);

    /**
     * Существующая контекстная connection данного scope (для find-or-create при binding'е:
     * командная/пользовательская/агентская). {@code (connector_code, scope_id)} среди активных.
     */
    @Query("""
            SELECT c FROM Connection c
            WHERE c.connectorCode = :connectorCode
              AND c.scopeId = :scopeId
              AND c.deletedAt IS NULL
            """)
    Optional<Connection> findActiveByConnectorCodeAndScopeId(@Param("connectorCode") String connectorCode,
                                                             @Param("scopeId") UUID scopeId);

    boolean existsByConnectorCodeAndUserIdAndSubCodeAndDeletedAtIsNull(
            String connectorCode, UUID userId, String subCode);

    @Modifying
    @Query("UPDATE Connection c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE Connection c SET c.lastUsedAt = :now WHERE c.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
