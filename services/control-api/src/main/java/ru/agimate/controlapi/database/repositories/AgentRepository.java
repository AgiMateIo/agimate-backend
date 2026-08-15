package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.Agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByKeyId(String keyId);

    Page<Agent> findByUserId(UUID userId, Pageable pageable);

    @Query("""
            SELECT a FROM Agent a
            WHERE a.userId = :userId
              AND (:agenticTeamId IS NULL OR a.agenticTeamId = :agenticTeamId)
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(a.description) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<Agent> searchForUser(
            @Param("userId") UUID userId,
            @Param("agenticTeamId") UUID agenticTeamId,
            @Param("search") String search,
            Pageable pageable);

    /**
     * The messenger's contact list: the user's agents ordered by how fresh their webchat is, the
     * ones never written to at the tail. The ordering key is an aggregate over another table, which
     * is why it cannot be assembled from {@link #searchForUser} plus a client-side merge — across
     * pages the order would not hold.
     *
     * <p>Native, so {@code @SQLRestriction} does not apply: the {@code deleted_at} of the agent is
     * spelled out here.
     *
     * @return rows of {@code (id, name, description, enabled, chat_activity_at)};
     *         {@code chat_activity_at} is null for an agent with no conversations yet
     */
    @Query(value = """
            SELECT a.id, a.name, a.description, a.enabled, MAX(s.last_activity_at) AS chat_activity_at
            FROM agents a
            LEFT JOIN channels c ON c.agent_id = a.id
                 AND c.connector_code = :connectorCode AND c.deleted_at IS NULL
            LEFT JOIN agent_sessions s ON s.channel_id = c.id
            WHERE a.user_id = :userId AND a.deleted_at IS NULL
            GROUP BY a.id
            ORDER BY MAX(s.last_activity_at) DESC NULLS LAST, a.created_at DESC, a.id DESC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM agents a
                    WHERE a.user_id = :userId AND a.deleted_at IS NULL
                    """,
            nativeQuery = true)
    Page<Object[]> findChatContacts(@Param("userId") UUID userId,
                                    @Param("connectorCode") String connectorCode,
                                    Pageable pageable);

    /**
     * Candidate recipients of a trigger: the user's agents with an active binding to the connection
     * (= the trigger's connectionId). Finer filtering (effect/params_filter) happens in
     * {@code ConnectionAccessEvaluator}.
     */
    @Query("""
            SELECT a FROM Agent a, AgentConnection ac, Connection c
            WHERE ac.agentId = a.id
              AND ac.connectionId = c.id
              AND c.id = :connectionId
              AND ac.deletedAt IS NULL
              AND c.deletedAt IS NULL
              AND c.enabled = true
              AND a.userId = :userId
              AND a.enabled = true
            ORDER BY a.createdAt, a.id
            """)
    List<Agent> findBoundToConnection(
            @Param("userId") UUID userId,
            @Param("connectionId") UUID connectionId);

    /** Names of the user's active agents (deleted ones are hidden by @SQLRestriction) — for deduplicating a new name. */
    @Query("SELECT a.name FROM Agent a WHERE a.userId = :userId")
    List<String> findNamesByUserId(@Param("userId") UUID userId);

    List<Agent> findByUserIdAndAgenticTeamId(UUID userId, UUID agenticTeamId);

    Page<Agent> findByUserIdAndAgenticTeamId(UUID userId, UUID agenticTeamId, Pageable pageable);

    boolean existsByAgenticTeamId(UUID agenticTeamId);

    @Query("""
            SELECT a FROM Agent a
            WHERE a.userId = :userId
              AND a.id IN (
                  SELECT s.agentId FROM AgentSkill s WHERE s.skillId = :skillId
              )
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(a.instructions) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<Agent> findBySkillId(
            @Param("skillId") UUID skillId,
            @Param("userId") UUID userId,
            @Param("search") String search,
            Pageable pageable);
}
