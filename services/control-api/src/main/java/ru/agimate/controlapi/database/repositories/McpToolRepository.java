package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.McpTool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpToolRepository extends JpaRepository<McpTool, UUID> {

    List<McpTool> findByIntegrationCredentialsId(UUID integrationCredentialsId);

    Optional<McpTool> findByIntegrationCredentialsIdAndName(UUID integrationCredentialsId, String name);

    @Modifying
    @Query("DELETE FROM McpTool t WHERE t.integrationCredentialsId = :id")
    int deleteByIntegrationCredentialsId(@Param("id") UUID integrationCredentialsId);
}
