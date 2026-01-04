package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.Credential;
import ru.agimate.connectorsapi.database.projections.CredentialShortInfoProjection;
import ru.agimate.connectorsapi.database.projections.CredentialsSummaryProjection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CredentialRepository extends JpaRepository<Credential, Long> {

    Optional<Credential> findByPubId(UUID pubId);

    @Query("SELECT c FROM Credential c WHERE c.pubId = :pubId AND c.deletedAt IS NULL")
    Optional<Credential> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT c FROM Credential c WHERE c.connector.code = :connectorCode AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Credential> findByConnectorCodeNotDeleted(@Param("connectorCode") String connectorCode);

    @Query("SELECT c FROM Credential c WHERE c.connector.code = :connectorCode AND c.userPubId = :userPubId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Credential> findByConnectorCodeAndUserPubIdNotDeleted(@Param("connectorCode") String connectorCode, @Param("userPubId") UUID userPubId);

    @Query("""
                SELECT
                    c.pubId as pubId,
                    c.name as name,
                    c.description as description,
                    con.code as connectorCode
                FROM Credential c
                JOIN c.connector con
                WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL
                ORDER BY c.createdAt DESC
            """)
    List<CredentialShortInfoProjection> findShortInfoByUserPubId(@Param("userPubId") UUID userPubId);

    @Query("""
                SELECT
                    c.pubId as pubId,
                    c.name as name,
                    c.description as description,
                    con.code as connectorCode
                FROM Credential c
                JOIN c.connector con
                WHERE c.userPubId = :userPubId AND con.code = :connectorCode AND c.deletedAt IS NULL
                ORDER BY c.createdAt DESC
            """)
    List<CredentialShortInfoProjection> findShortInfoByUserPubIdAndConnectorCode(@Param("userPubId") UUID userPubId, @Param("connectorCode") String connectorCode);

    @Query("SELECT c FROM Credential c WHERE c.pubId = :pubId AND c.userPubId = :userPubId AND c.deletedAt IS NULL")
    Optional<Credential> findByPubIdAndUserPubIdNotDeleted(@Param("pubId") UUID pubId, @Param("userPubId") UUID userPubId);

    @Query("SELECT COUNT(c) FROM Credential c WHERE c.connector.code = :connectorCode AND c.deletedAt IS NULL")
    long countByConnectorCodeNotDeleted(@Param("connectorCode") String connectorCode);

    @Modifying
    @Query("UPDATE Credential c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE Credential c SET c.lastUsedAt = :now WHERE c.id = :id")
    void updateLastUsedAt(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("""
                SELECT
                    con.code as connectorCode,
                    con.name as connectorName,
                    COUNT(cr.id) as credentialCount,
                    MAX(cr.createdAt) as lastAddedAt,
                    MAX(cr.lastUsedAt) as lastUsedAt
                FROM Connector con
                LEFT JOIN Credential cr ON cr.connector = con AND cr.deletedAt IS NULL
                WHERE con.enabled = true
                GROUP BY con.id, con.code, con.name
                HAVING COUNT(cr.id) > 0
                ORDER BY con.name
            """)
    List<CredentialsSummaryProjection> findCredentialsSummary();

    @Query("""
                SELECT
                    con.code as connectorCode,
                    con.name as connectorName,
                    COUNT(cr.id) as credentialCount,
                    MAX(cr.createdAt) as lastAddedAt,
                    MAX(cr.lastUsedAt) as lastUsedAt
                FROM Connector con
                LEFT JOIN Credential cr ON cr.connector = con AND cr.deletedAt IS NULL AND cr.userPubId = :userPubId
                WHERE con.enabled = true
                GROUP BY con.id, con.code, con.name
                HAVING COUNT(cr.id) > 0
                ORDER BY con.name
            """)
    List<CredentialsSummaryProjection> findCredentialsSummaryByUser(@Param("userPubId") UUID userPubId);
}
