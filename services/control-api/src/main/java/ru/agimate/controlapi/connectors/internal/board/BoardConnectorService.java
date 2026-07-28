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
            .guidance("Below is an event from your team's kanban board. React only if it requires "
                    + "action from you specifically: you are the assigned owner, a question was "
                    + "addressed to you, or your next step is needed. Do not react to your own actions "
                    + "(actorAgentId == your id). Details and actions go through the board connector's "
                    + "tools. Anything you want to tell the team or the user about the task must be "
                    + "written strictly as a board comment (create_comment): this run's final text is "
                    + "delivered nowhere and will be read by no one — it only serves as a short "
                    + "internal summary.")
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
        return "The team's Kanban board: tasks, statuses, owners and comments — "
                + "the agent keeps them itself and reacts to changes.";
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
