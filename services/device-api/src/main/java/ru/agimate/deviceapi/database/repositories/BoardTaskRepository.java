package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.BoardTask;

import java.util.List;
import java.util.UUID;

public interface BoardTaskRepository extends JpaRepository<BoardTask, UUID> {

    List<BoardTask> findByBoardIdOrderByCreatedAtDesc(UUID boardId);

    List<BoardTask> findByParentTaskId(UUID parentTaskId);
}
