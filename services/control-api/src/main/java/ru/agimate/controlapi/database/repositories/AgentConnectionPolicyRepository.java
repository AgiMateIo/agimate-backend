package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.enums.PolicyKind;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentConnectionPolicyRepository extends JpaRepository<AgentConnectionPolicy, UUID> {

    /**
     * Правила, релевантные разрешению {@code (kind, name)}: точное имя + binding-wide ({@code name IS
     * NULL}). Упорядочены так, что точное имя идёт первым — эвалуатор берёт {@code get(0)} как
     * победителя (прецеденс «имя > wildcard»).
     */
    @Query("""
            SELECT p FROM AgentConnectionPolicy p
            WHERE p.agentConnectionId = :agentConnectionId
              AND p.kind = :kind
              AND (p.name = :name OR p.name IS NULL)
              AND p.deletedAt IS NULL
            ORDER BY CASE WHEN p.name IS NULL THEN 1 ELSE 0 END
            """)
    List<AgentConnectionPolicy> resolve(@Param("agentConnectionId") UUID agentConnectionId,
                                        @Param("kind") PolicyKind kind,
                                        @Param("name") String name);

    @Query("""
            SELECT p FROM AgentConnectionPolicy p
            WHERE p.agentConnectionId = :agentConnectionId AND p.deletedAt IS NULL
            ORDER BY p.kind, p.name NULLS FIRST
            """)
    List<AgentConnectionPolicy> findActiveByAgentConnectionId(@Param("agentConnectionId") UUID agentConnectionId);

    @Query("""
            SELECT p FROM AgentConnectionPolicy p
            WHERE p.agentConnectionId = :agentConnectionId
              AND p.kind = :kind
              AND ((:name IS NULL AND p.name IS NULL) OR p.name = :name)
              AND p.deletedAt IS NULL
            """)
    Optional<AgentConnectionPolicy> findActive(@Param("agentConnectionId") UUID agentConnectionId,
                                               @Param("kind") PolicyKind kind,
                                               @Param("name") String name);

    @Modifying
    @Query("UPDATE AgentConnectionPolicy p SET p.deletedAt = :now WHERE p.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE AgentConnectionPolicy p SET p.deletedAt = :now WHERE p.agentConnectionId = :agentConnectionId")
    void softDeleteByAgentConnectionId(@Param("agentConnectionId") UUID agentConnectionId,
                                       @Param("now") LocalDateTime now);
}
