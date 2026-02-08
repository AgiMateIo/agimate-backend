package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.ConnectorCredential;
import ru.agimate.connectorsapi.database.projections.ConnectorCredentialShortInfoProjection;
import ru.agimate.connectorsapi.database.projections.ConnectorCredentialsSummaryProjection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorCredentialRepository extends JpaRepository<ConnectorCredential, Long> {

    Optional<ConnectorCredential> findByPubId(UUID pubId);

    @Query("SELECT c FROM ConnectorCredential c WHERE c.pubId = :pubId AND c.deletedAt IS NULL")
    Optional<ConnectorCredential> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT c FROM ConnectorCredential c WHERE c.connector.code = :connectorCode AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<ConnectorCredential> findByConnectorCodeNotDeleted(@Param("connectorCode") String connectorCode);

    @Query("SELECT c FROM ConnectorCredential c WHERE c.connector.code = :connectorCode AND c.userPubId = :userPubId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<ConnectorCredential> findByConnectorCodeAndUserPubIdNotDeleted(@Param("connectorCode") String connectorCode, @Param("userPubId") UUID userPubId);

    @Query("""
                SELECT
                    c.pubId as pubId,
                    c.name as name,
                    c.description as description,
                    con.code as connectorCode
                FROM ConnectorCredential c
                JOIN c.connector con
                WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL
                ORDER BY c.createdAt DESC
            """)
    List<ConnectorCredentialShortInfoProjection> findShortInfoByUserPubId(@Param("userPubId") UUID userPubId);

    @Query("""
                SELECT
                    c.pubId as pubId,
                    c.name as name,
                    c.description as description,
                    con.code as connectorCode
                FROM ConnectorCredential c
                JOIN c.connector con
                WHERE c.userPubId = :userPubId AND con.code = :connectorCode AND c.deletedAt IS NULL
                ORDER BY c.createdAt DESC
            """)
    List<ConnectorCredentialShortInfoProjection> findShortInfoByUserPubIdAndConnectorCode(@Param("userPubId") UUID userPubId, @Param("connectorCode") String connectorCode);

    @Query("SELECT c FROM ConnectorCredential c WHERE c.pubId = :pubId AND c.userPubId = :userPubId AND c.deletedAt IS NULL")
    Optional<ConnectorCredential> findByPubIdAndUserPubIdNotDeleted(@Param("pubId") UUID pubId, @Param("userPubId") UUID userPubId);

    @Query("SELECT COUNT(c) FROM ConnectorCredential c WHERE c.connector.code = :connectorCode AND c.deletedAt IS NULL")
    long countByConnectorCodeNotDeleted(@Param("connectorCode") String connectorCode);

    @Modifying
    @Query("UPDATE ConnectorCredential c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE ConnectorCredential c SET c.lastUsedAt = :now WHERE c.id = :id")
    void updateLastUsedAt(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("""
                SELECT
                    con.code as connectorCode,
                    con.name as connectorName,
                    COUNT(cr.id) as credentialCount,
                    MAX(cr.createdAt) as lastAddedAt,
                    MAX(cr.lastUsedAt) as lastUsedAt
                FROM Connector con
                LEFT JOIN ConnectorCredential cr ON cr.connector = con AND cr.deletedAt IS NULL
                WHERE con.enabled = true
                GROUP BY con.id, con.code, con.name
                HAVING COUNT(cr.id) > 0
                ORDER BY con.name
            """)
    List<ConnectorCredentialsSummaryProjection> findCredentialsSummary();

    @Query("""
                SELECT
                    con.code as connectorCode,
                    con.name as connectorName,
                    COUNT(cr.id) as credentialCount,
                    MAX(cr.createdAt) as lastAddedAt,
                    MAX(cr.lastUsedAt) as lastUsedAt
                FROM Connector con
                LEFT JOIN ConnectorCredential cr ON cr.connector = con AND cr.deletedAt IS NULL AND cr.userPubId = :userPubId
                WHERE con.enabled = true
                GROUP BY con.id, con.code, con.name
                HAVING COUNT(cr.id) > 0
                ORDER BY con.name
            """)
    List<ConnectorCredentialsSummaryProjection> findCredentialsSummaryByUser(@Param("userPubId") UUID userPubId);
}
