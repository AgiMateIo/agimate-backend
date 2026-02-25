package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.Board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, Long> {

    Optional<Board> findByPubId(UUID pubId);

    List<Board> findByUserPubId(UUID userPubId);

    boolean existsByAgenticTeamId(Long agenticTeamId);

    Optional<Board> findByAgenticTeamId(Long agenticTeamId);
}
