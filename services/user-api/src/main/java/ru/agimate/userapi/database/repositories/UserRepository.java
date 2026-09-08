package ru.agimate.userapi.database.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.UserEntity;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {
    Optional<UserEntity> findByEmail(String email);

    /**
     * The row, locked until the transaction ends. For the one decision that reads and writes in two
     * statements and must not be raced: whether an account still has another way in after this one
     * is removed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<UserEntity> findByReferralCode(String referralCode);

    boolean existsByReferralCode(String referralCode);

    long countByReferredBy(UUID referrerId);

    /** Registrations since the given moment, whatever role they ended up with. */
    long countByCreatedAtGreaterThanEqual(LocalDateTime from);

    /**
     * Serializes the daily admission decision until the caller's transaction ends: counting the
     * registrations of the day and inserting one more are two statements, and two signups landing
     * between them would both see the same place free. The key names this decision and nothing
     * else. The lock is taken through a select of its own because the function returns
     * {@code void}, which is not a column Hibernate can map.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(4210)) locked", nativeQuery = true)
    int lockDailyAdmissions();
}
