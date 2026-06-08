package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.SkillConnector;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SkillConnectorRepository extends JpaRepository<SkillConnector, UUID> {

    List<SkillConnector> findBySkillId(UUID skillId);

    @Query("SELECT sc FROM SkillConnector sc JOIN FETCH sc.skill s WHERE s.id IN :skillIds")
    List<SkillConnector> findBySkillIdIn(@Param("skillIds") Collection<UUID> skillIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SkillConnector sc WHERE sc.skill.id = :skillId")
    void deleteBySkillId(@Param("skillId") UUID skillId);
}
