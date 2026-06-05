package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.AgenticTeam;

import java.util.List;
import java.util.UUID;

public interface AgenticTeamRepository extends JpaRepository<AgenticTeam, UUID> {

    List<AgenticTeam> findByUserId(UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);
}
