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
 * Facade of the board connector: the tools live in {@link BoardToolService}; the triggers are emitted
 * by the core {@link BoardService}, and what lives here are their static declarations
 * ({@link TriggerSpec} plus context directives).
 *
 * <p><b>The data owner is the calling agent's team</b>: the board is resolved
 * {@code env.agentId → agenticTeam → board}, with no separate referent in the connection (see the
 * axis checklist in docs/architecture/connectors.md).
 */
@Component
public class BoardConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider {

    /**
     * A board event is actionable through the board's tools regardless of the agent's skills
     * ({@code ownConnectionTools}); the guidance carries provenance and the rules of reaction: do not
     * respond to your own actions, and communicate strictly through board comments — a board-trigger
     * run has no channel, so its final text is delivered to nobody.
     */
    private static final ContextDirectives BOARD_EVENT_CONTEXT = ContextDirectives.builder()
            .ownConnectionTools(true)
            .guidance("Ниже — событие kanban-доски твоей команды. Реагируй, только если оно требует "
                    + "действий именно от тебя: ты назначен исполнителем, задан вопрос тебе или нужен "
                    + "твой следующий шаг. На собственные действия (actorAgentId == твой id) не "
                    + "реагируй. Детали и действия — тулами board-коннектора. Всё, что хочешь "
                    + "сообщить по задаче команде или пользователю, пиши строго комментарием на доске "
                    + "(create_comment): финальный текст этого рана никуда не доставляется и никем "
                    + "не будет прочитан — он годится только как короткое служебное резюме.")
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
    public String connectorDescription() {
        return "Канбан-доска команды: задачи, статусы, исполнители и комментарии — "
                + "агент ведёт их сам и реагирует на изменения.";
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
