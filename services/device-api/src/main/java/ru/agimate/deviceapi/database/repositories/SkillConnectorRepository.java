package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.SkillConnector;

import java.util.List;
import java.util.UUID;

@Repository
public interface SkillConnectorRepository extends JpaRepository<SkillConnector, UUID> {

    List<SkillConnector> findBySkillId(Long skillId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SkillConnector sc WHERE sc.skill.id = :skillId")
    void deleteBySkillId(@Param("skillId") Long skillId);
}
