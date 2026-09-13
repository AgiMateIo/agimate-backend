package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ConnectionTriggerRepository extends JpaRepository<ConnectionTrigger, UUID> {

    @Query("SELECT t FROM ConnectionTrigger t WHERE t.connectionId = :connectionId AND t.deletedAt IS NULL")
    List<ConnectionTrigger> findActiveByConnectionId(@Param("connectionId") UUID connectionId);

    /** Which of the given connections have at least one live trigger — the counterpart of the tools query in the skill gate. */
    @Query("SELECT DISTINCT t.connectionId FROM ConnectionTrigger t WHERE t.connectionId IN :ids AND t.deletedAt IS NULL")
    Set<UUID> findIdsWithActiveTriggers(@Param("ids") Collection<UUID> ids);

    @Query("SELECT COUNT(t) > 0 FROM ConnectionTrigger t WHERE t.connectionId = :connectionId AND t.name = :name AND t.deletedAt IS NULL")
    boolean existsActiveByConnectionIdAndName(@Param("connectionId") UUID connectionId, @Param("name") String name);

    @Modifying
    @Query("DELETE FROM ConnectionTrigger t WHERE t.connectionId = :connectionId")
    int deleteByConnectionId(@Param("connectionId") UUID connectionId);
}
