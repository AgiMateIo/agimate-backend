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
     * Кандидаты-получатели триггера: агенты пользователя с активным binding на connection
     * (= identity триггера). Тонкая фильтрация (effect/params_filter) — в {@code ConnectionAccessEvaluator}.
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
