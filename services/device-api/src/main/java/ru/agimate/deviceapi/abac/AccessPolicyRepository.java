package ru.agimate.deviceapi.abac;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AccessPolicyRepository extends JpaRepository<AccessPolicy, UUID> {

    List<AccessPolicy> findByAgentName(String agentName);

    @Query(value = """
            SELECT * FROM access_policies
            WHERE agent_name = :agentName
              AND (connector_name IS NULL OR connector_name = :connectorName)
              AND (connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR connector_identity = :connectorIdentity)
              AND (tool_name IS NULL OR tool_name = :toolName)
            """, nativeQuery = true)
    List<AccessPolicy> findMatchingPolicies(
            @Param("agentName") String agentName,
            @Param("connectorName") String connectorName,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("toolName") String toolName
    );
}
