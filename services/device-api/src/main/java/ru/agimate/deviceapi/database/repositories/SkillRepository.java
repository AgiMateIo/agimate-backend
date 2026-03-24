package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Skill;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillRepository extends JpaRepository<Skill, Long>, JpaSpecificationExecutor<Skill> {

    @Query("SELECT s FROM Skill s WHERE s.pubId = :pubId AND s.deletedAt IS NULL")
    Optional<Skill> findByPubIdNotDeleted(@Param("pubId") UUID pubId);

    @Query("SELECT COUNT(s) > 0 FROM Skill s WHERE s.userPubId = :userPubId AND s.name = :name AND s.deletedAt IS NULL")
    boolean existsByUserPubIdAndNameNotDeleted(@Param("userPubId") UUID userPubId, @Param("name") String name);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Skill s SET s.deletedAt = :now WHERE s.id = :id")
    void softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Skill s SET s.updatedAt = :now WHERE s.id = :id AND s.deletedAt IS NULL")
    void touchUpdatedAt(@Param("id") Long id, @Param("now") LocalDateTime now);
}
