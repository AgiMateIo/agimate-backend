package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Skill;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillRepository extends JpaRepository<Skill, UUID>, JpaSpecificationExecutor<Skill> {

    @Query("SELECT s FROM Skill s WHERE s.id = :id AND s.deletedAt IS NULL")
    Optional<Skill> findByIdNotDeleted(@Param("id") UUID id);

    @Query("SELECT COUNT(s) > 0 FROM Skill s WHERE s.userId = :userId AND s.name = :name AND s.deletedAt IS NULL")
    boolean existsByUserIdAndNameNotDeleted(@Param("userId") UUID userId, @Param("name") String name);

    @Query("SELECT s FROM Skill s WHERE s.id IN :ids AND s.deletedAt IS NULL")
    List<Skill> findByIdInNotDeleted(@Param("ids") Collection<UUID> ids);

    @Query("""
            SELECT s.id, s.name, s.description, sc
            FROM Skill s
            LEFT JOIN SkillConnector sc ON sc.skill = s
            WHERE s.id IN :ids AND s.deletedAt IS NULL
            """)
    List<Object[]> findNamesAndConnectorsByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("SELECT s FROM Skill s WHERE s.userId = :userId AND s.name = :name AND s.deletedAt IS NULL")
    Optional<Skill> findByUserIdAndNameNotDeleted(@Param("userId") UUID userId, @Param("name") String name);

    @Query("SELECT COUNT(s) > 0 FROM Skill s WHERE s.id = :id AND s.isFeatured = true AND s.deletedAt IS NULL")
    boolean existsByIdAndIsFeaturedTrue(@Param("id") UUID id);

    @Query("SELECT s.parentId, s.id FROM Skill s WHERE s.parentId IN :parentIds AND s.userId = :userId AND s.deletedAt IS NULL")
    List<Object[]> findMyClonesByParentIds(@Param("parentIds") Collection<UUID> parentIds, @Param("userId") UUID userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Skill s SET s.deletedAt = :now WHERE s.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Skill s SET s.updatedAt = :now WHERE s.id = :id AND s.deletedAt IS NULL")
    void touchUpdatedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
