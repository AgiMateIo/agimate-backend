package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.BoardTask;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;

import java.util.List;
import java.util.UUID;

public interface BoardTaskRepository extends JpaRepository<BoardTask, UUID> {

    /** The board's tasks, newest first; a {@code null} filter means no narrowing. The only listing:
     * the filters belong in the query, not in a full board read narrowed afterwards. */
    @Query("""
            SELECT t FROM BoardTask t
            WHERE t.boardId = :boardId
              AND (:status IS NULL OR t.status = :status)
              AND (:assigneeAgentId IS NULL OR t.assigneeAgentId = :assigneeAgentId)
            ORDER BY t.createdAt DESC
            """)
    List<BoardTask> findByBoardIdFiltered(@Param("boardId") UUID boardId,
                                          @Param("status") BoardTaskStatus status,
                                          @Param("assigneeAgentId") UUID assigneeAgentId);

    List<BoardTask> findByParentTaskId(UUID parentTaskId);
}
