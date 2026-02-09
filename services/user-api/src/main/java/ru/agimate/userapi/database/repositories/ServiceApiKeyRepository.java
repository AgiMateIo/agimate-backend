package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.ServiceApiKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceApiKeyRepository extends JpaRepository<ServiceApiKey, Long> {

    Optional<ServiceApiKey> findByPubId(UUID pubId);

    @Query("SELECT c FROM ServiceApiKey c WHERE c.pubId = :pubId AND c.deletedAt IS NULL")
    Optional<ServiceApiKey> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT c FROM ServiceApiKey c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<ServiceApiKey> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT c FROM ServiceApiKey c WHERE c.keyId = :keyId AND c.deletedAt IS NULL AND c.enabled = true")
    Optional<ServiceApiKey> findActiveKeyByKeyId(@Param("keyId") String keyId);

    @Query("SELECT COUNT(c) FROM ServiceApiKey c WHERE c.userPubId = :userPubId AND c.deletedAt IS NULL")
    long countByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Modifying
    @Query("UPDATE ServiceApiKey c SET c.deletedAt = :now WHERE c.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(c) > 0 FROM ServiceApiKey c WHERE c.userPubId = :userPubId AND c.name = :name AND c.deletedAt IS NULL")
    boolean existsByUserPubIdAndName(@Param("userPubId") UUID userPubId, @Param("name") String name);
}
