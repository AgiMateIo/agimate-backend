package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.BoardTask;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardTaskRepository extends JpaRepository<BoardTask, Long> {

    Optional<BoardTask> findByPubId(UUID pubId);

    List<BoardTask> findByBoardIdOrderByCreatedAtDesc(Long boardId);

    List<BoardTask> findByParentTaskId(Long parentTaskId);
}
