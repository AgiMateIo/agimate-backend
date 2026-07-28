package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.Connection;

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

    /** A user's connections with optional filters on real fields (null = do not filter). */
    @Query("""
            SELECT c FROM Connection c
            WHERE c.userId = :userId
              AND c.deletedAt IS NULL
              AND (:connectorCode IS NULL OR c.connectorCode = :connectorCode)
              AND (:enabled IS NULL OR c.enabled = :enabled)
            ORDER BY c.connectorCode, c.createdAt DESC
            """)
    List<Connection> findByUserIdFiltered(
            @Param("userId") UUID userId,
            @Param("connectorCode") String connectorCode,
            @Param("enabled") Boolean enabled);

    @Query("""
            SELECT c FROM Connection c
            WHERE c.connectorCode = :connectorCode
              AND c.enabled = true
              AND c.deletedAt IS NULL
            """)
    List<Connection> findActiveByConnectorCode(@Param("connectorCode") String connectorCode);

    Optional<Connection> findByAppIdAndDeletedAtIsNull(UUID appId);

    /** Active instances bound to an agent through {@code agent_connections} (the availability gate). */
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
     * Atomic materialisation of a mode row: an INSERT that silently loses the race on the partial
     * index {@code uq_connections_full_code_user}. Returns 1 when this call created the row (the
     * signal to emit a {@code ConnectorCreatedEvent}), 0 when a concurrent winner got there first
     * (its row becomes visible to the next SELECT after its commit). Native and via ON CONFLICT
     * deliberately: Hibernate defers the INSERT until flush, so a uniqueness violation would surface
     * outside the handler — and after it the PostgreSQL transaction is poisoned and a re-read inside
     * it is impossible.
     */
    @Modifying
    @Query(value = """
            INSERT INTO connections (id, connector_code, full_code, user_id, name, enabled)
            VALUES (:id, :connectorCode, :fullCode, :userId, :name, true)
            ON CONFLICT (full_code, user_id) WHERE deleted_at IS NULL DO NOTHING
            """, nativeQuery = true)
    int insertModeConnectionIfAbsent(@Param("id") UUID id,
                                     @Param("connectorCode") String connectorCode,
                                     @Param("fullCode") String fullCode,
                                     @Param("userId") UUID userId,
                                     @Param("name") String name);

    boolean existsByConnectorCodeAndUserIdAndSubCodeAndDeletedAtIsNull(
            String connectorCode, UUID userId, String subCode);

    @Modifying
    @Query("UPDATE Connection c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    // @Transactional here: the only caller comes from the tools' async pool, where there is no outer
    // transaction, and Hibernate rejects @Modifying with no active TX.
    @Transactional
    @Modifying
    @Query("UPDATE Connection c SET c.lastUsedAt = :now WHERE c.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
