package ru.agimate.controlapi.connectors.internal.board;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.service.board.BoardService;

import java.util.List;
import java.util.Map;

/**
 * Фасад board-коннектора: тулы живут в {@link BoardToolService}; триггеры эмитит core-{@link BoardService},
 * здесь — их статические декларации ({@link TriggerSpec} + директивы контекста).
 *
 * <p><b>Владелец данных — команда вызывающего агента</b>: доска резолвится
 * {@code env.agentId → agenticTeam → board}, отдельного референта в connection нет
 * (см. чек-лист осей в docs/connectors/architecture.md).
 */
@Component
public class BoardConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider {

    /**
     * Board-событие действуемо тулами доски независимо от скиллов агента ({@code ownConnectionTools});
     * guidance — провенанс и правило реакции (не отвечать на собственные действия).
     */
    private static final ContextDirectives BOARD_EVENT_CONTEXT = ContextDirectives.builder()
            .ownConnectionTools(true)
            .guidance("Ниже — событие kanban-доски твоей команды. Реагируй, только если оно требует "
                    + "действий именно от тебя: ты назначен исполнителем, задан вопрос тебе или нужен "
                    + "твой следующий шаг. На собственные действия (actorAgentId == твой id) не "
                    + "реагируй. Детали и действия — тулами board-коннектора.")
            .build();

    public BoardConnectorService(BoardToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return BoardService.CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Board";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(
                BoardService.TASK_CREATED_TRIGGER, new TriggerSpec(
                        "A task was created on the team's kanban board",
                        List.of("boardId", "taskId", "type", "title", "description",
                                "createdByAgentId", "assigneeAgentId", "parentTaskId", "parentTaskTitle"),
                        BOARD_EVENT_CONTEXT),
                BoardService.TASK_CHANGED_TRIGGER, new TriggerSpec(
                        "A board task changed: change=status (status moved, see previousStatus), "
                                + "change=comment (a comment was added) or change=edited "
                                + "(fields edited, see changedFields)",
                        List.of("boardId", "taskId", "type", "title", "status", "change",
                                "previousStatus", "commentId", "comment", "changedFields",
                                "previousAssigneeAgentId", "actorAgentId",
                                "assigneeAgentId", "parentTaskId"),
                        BOARD_EVENT_CONTEXT));
    }
}
