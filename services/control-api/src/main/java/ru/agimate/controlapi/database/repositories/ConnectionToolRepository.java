package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectionToolRepository extends JpaRepository<ConnectionTool, UUID> {

    @Query("SELECT t FROM ConnectionTool t WHERE t.connectionId = :connectionId AND t.deletedAt IS NULL")
    List<ConnectionTool> findActiveByConnectionId(@Param("connectionId") UUID connectionId);

    @Query("""
            SELECT t FROM ConnectionTool t
            WHERE t.connectionId = :connectionId AND t.name = :name AND t.deletedAt IS NULL
            """)
    Optional<ConnectionTool> findActiveByConnectionIdAndName(
            @Param("connectionId") UUID connectionId, @Param("name") String name);

    @Modifying
    @Query("DELETE FROM ConnectionTool t WHERE t.connectionId = :connectionId")
    int deleteByConnectionId(@Param("connectionId") UUID connectionId);
}
