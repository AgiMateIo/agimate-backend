package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    /** The team comes along: the listing shows its name, and a lazy load per board would be an N+1. */
    @Query("SELECT b FROM Board b JOIN FETCH b.agenticTeam WHERE b.userId = :userId")
    List<Board> findByUserId(@Param("userId") UUID userId);

    boolean existsByAgenticTeam(AgenticTeam agenticTeam);

    Optional<Board> findByAgenticTeam(AgenticTeam agenticTeam);
}
