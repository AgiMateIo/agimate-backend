package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.CreateBoardRequest;
import ru.agimate.controlapi.controller.manage.dto.CreateBoardTaskCommentRequest;
import ru.agimate.controlapi.controller.manage.dto.CreateBoardTaskRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateBoardTaskStatusRequest;
import ru.agimate.controlapi.service.board.BoardService;
import ru.agimate.controlapi.service.dto.board.BoardCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskStatusChangeCommand;
import ru.agimate.controlapi.service.dto.board.BoardTasksByStatusResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageBoardController.PATH)
@RequiredArgsConstructor
@Tag(name = "Boards", description = "Manage task boards")
public class ManageBoardController {

    public static final String PATH = "/manage/boards";

    private final BoardService boardService;

    @Operation(summary = "List boards for the current user")
    @GetMapping("/")
    public SuccessResponse<List<BoardResponse>> getBoards(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(boardService.getAllForUser(userId));
    }

    @Operation(summary = "Get board by ID")
    @GetMapping("/{boardId}")
    public SuccessResponse<BoardResponse> getBoard(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(boardService.getById(boardId, userId));
    }

    @Operation(summary = "Create a board for an agentic team")
    @PostMapping("/")
    public SuccessResponse<BoardResponse> createBoard(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateBoardRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        BoardCreateCommand command = new BoardCreateCommand(
                request.agenticTeamId(), request.name(), request.description());
        return SuccessResponse.ok(boardService.create(userId, command));
    }

    @Operation(summary = "Get board tasks grouped by status")
    @GetMapping("/{boardId}/tasks/")
    public SuccessResponse<BoardTasksByStatusResponse> getTasksByStatus(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(boardService.getTasksByStatus(boardId, userId));
    }

    @Operation(summary = "Create a task on the board")
    @PostMapping("/{boardId}/tasks/")
    public SuccessResponse<BoardTaskResponse> createTask(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateBoardTaskRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        BoardTaskCreateCommand command = new BoardTaskCreateCommand(
                request.type(), request.title(), request.description(),
                request.createdByAgentId(), request.assigneeAgentId(), request.parentTaskId());
        return SuccessResponse.ok(boardService.createTask(boardId, userId, command));
    }

    @Operation(summary = "Change task status")
    @PatchMapping("/{boardId}/tasks/{taskId}/status")
    public SuccessResponse<BoardTaskResponse> changeTaskStatus(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateBoardTaskStatusRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        BoardTaskStatusChangeCommand command = new BoardTaskStatusChangeCommand(
                request.status(), request.agentId());
        return SuccessResponse.ok(boardService.changeTaskStatus(boardId, taskId, userId, command));
    }

    @Operation(summary = "Get comments for a task")
    @GetMapping("/{boardId}/tasks/{taskId}/comments/")
    public SuccessResponse<List<BoardTaskCommentResponse>> getComments(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(boardService.getComments(boardId, taskId, userId));
    }

    @Operation(summary = "Create a comment on a task")
    @PostMapping("/{boardId}/tasks/{taskId}/comments/")
    public SuccessResponse<BoardTaskCommentResponse> createComment(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateBoardTaskCommentRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        BoardTaskCommentCreateCommand command = new BoardTaskCommentCreateCommand(
                request.agentId(), request.content());
        return SuccessResponse.ok(boardService.createComment(boardId, taskId, userId, command));
    }
}
