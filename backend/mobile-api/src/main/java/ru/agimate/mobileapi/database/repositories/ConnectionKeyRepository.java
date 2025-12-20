package ru.agimate.mobileapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.mobileapi.database.entities.ConnectionKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectionKeyRepository extends JpaRepository<ConnectionKey, Long> {

    Optional<ConnectionKey> findByPubId(UUID pubId);

    @Query("SELECT ck FROM ConnectionKey ck WHERE ck.pubId = :pubId AND ck.deletedAt IS NULL")
    Optional<ConnectionKey> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT ck FROM ConnectionKey ck WHERE ck.userPubId = :userPubId AND ck.deletedAt IS NULL ORDER BY ck.createdAt DESC")
    List<ConnectionKey> findByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Query("SELECT ck FROM ConnectionKey ck WHERE ck.keyId = :keyId AND ck.deletedAt IS NULL AND ck.enabled = true")
    Optional<ConnectionKey> findActiveKeyByKeyId(@Param("keyId") String keyId);

    @Query("SELECT COUNT(ck) FROM ConnectionKey ck WHERE ck.userPubId = :userPubId AND ck.deletedAt IS NULL")
    long countByUserPubIdNotDeleted(@Param("userPubId") UUID userPubId);

    @Modifying
    @Query("UPDATE ConnectionKey ck SET ck.deletedAt = :now WHERE ck.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(ck) > 0 FROM ConnectionKey ck WHERE ck.userPubId = :userPubId AND ck.name = :name AND ck.deletedAt IS NULL")
    boolean existsByUserPubIdAndName(@Param("userPubId") UUID userPubId, @Param("name") String name);
}
