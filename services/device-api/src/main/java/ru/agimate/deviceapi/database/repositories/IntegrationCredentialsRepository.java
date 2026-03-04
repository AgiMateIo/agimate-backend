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
public interface IntegrationCredentialsRepository extends JpaRepository<IntegrationCredentials, Long> {

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.pubId = :pubId AND i.deletedAt IS NULL")
    Optional<IntegrationCredentials> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.userPubId = :userPubId AND i.deletedAt IS NULL ORDER BY i.createdAt DESC")
    List<IntegrationCredentials> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT i FROM IntegrationCredentials i WHERE i.connectorRegistryId = :connectorRegistryId AND i.deletedAt IS NULL")
    Optional<IntegrationCredentials> findByConnectorRegistryId(@Param("connectorRegistryId") Long connectorRegistryId);

    @Modifying
    @Query("UPDATE IntegrationCredentials i SET i.deletedAt = :now WHERE i.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE IntegrationCredentials i SET i.lastUsedAt = :now WHERE i.id = :id")
    void updateLastUsedAt(@Param("id") Long id, @Param("now") LocalDateTime now);
}
