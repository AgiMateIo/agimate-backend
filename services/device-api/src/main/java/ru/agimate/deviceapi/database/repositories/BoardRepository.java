package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.entities.Board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    List<Board> findByUserPubId(UUID userPubId);

    boolean existsByAgenticTeam(AgenticTeam agenticTeam);

    Optional<Board> findByAgenticTeam(AgenticTeam agenticTeam);
}
