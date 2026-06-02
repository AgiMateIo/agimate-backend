package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IntegrationCredentialsRepository extends JpaRepository<IntegrationCredentials, UUID> {

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<IntegrationCredentials> findByIdNotDeleted(@Param("id") UUID id);

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.userPubId = :userPubId AND i.deletedAt IS NULL ORDER BY i.createdAt DESC")
    List<IntegrationCredentials> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("""
            SELECT i FROM IntegrationCredentials i
            WHERE i.userPubId = :userPubId
              AND i.connectorCode = :connectorCode
              AND i.deletedAt IS NULL
            ORDER BY i.createdAt DESC
            """)
    List<IntegrationCredentials> findByUserPubIdAndConnectorCodeNotDeleted(
            @Param("userPubId") UUID userPubId,
            @Param("connectorCode") String connectorCode);

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.connectorCode = :connectorCode AND i.deletedAt IS NULL")
    Optional<IntegrationCredentials> findByConnectorCode(@Param("connectorCode") String connectorCode);

    @Query("""
            SELECT i FROM IntegrationCredentials i
            WHERE i.connectorCode = :connectorCode
              AND i.enabled = true
              AND i.deletedAt IS NULL
            """)
    List<IntegrationCredentials> findActiveByConnectorCode(@Param("connectorCode") String connectorCode);

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.id = :id AND i.userPubId = :userPubId AND i.deletedAt IS NULL")
    Optional<IntegrationCredentials> findByIdAndUserPubIdNotDeleted(@Param("id") UUID id, @Param("userPubId") UUID userPubId);

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.id IN :ids AND i.deletedAt IS NULL")
    List<IntegrationCredentials> findAllByIdInNotDeleted(@Param("ids") java.util.Collection<UUID> ids);

    boolean existsByConnectorCodeAndUserPubIdAndPlatformIdentifierAndDeletedAtIsNull(
            String connectorCode, UUID userPubId, String platformIdentifier);

    @Modifying
    @Query("UPDATE IntegrationCredentials i SET i.deletedAt = :now WHERE i.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE IntegrationCredentials i SET i.lastUsedAt = :now WHERE i.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
