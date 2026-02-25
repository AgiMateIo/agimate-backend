package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.BoardTaskComment;

import java.util.List;

public interface BoardTaskCommentRepository extends JpaRepository<BoardTaskComment, Long> {

    List<BoardTaskComment> findByBoardTaskIdOrderByCreatedAtAsc(Long boardTaskId);
}
