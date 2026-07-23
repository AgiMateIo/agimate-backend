package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.controlapi.database.entities.BoardTaskComment;

import java.util.List;
import java.util.UUID;

public interface BoardTaskCommentRepository extends JpaRepository<BoardTaskComment, UUID> {

    List<BoardTaskComment> findByBoardTaskIdOrderByCreatedAtAsc(UUID boardTaskId);

    List<BoardTaskComment> findTop10ByBoardTaskIdOrderByCreatedAtDesc(UUID boardTaskId);
}
