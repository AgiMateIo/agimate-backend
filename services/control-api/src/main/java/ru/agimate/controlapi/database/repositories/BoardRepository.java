package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    List<Board> findByUserId(UUID userId);

    boolean existsByAgenticTeam(AgenticTeam agenticTeam);

    Optional<Board> findByAgenticTeam(AgenticTeam agenticTeam);
}
