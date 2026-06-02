package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.connectors.internal.board.BoardService;

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
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.getAllForUser(userPubId));
    }

    @Operation(summary = "Get board by ID")
    @GetMapping("/{id}")
    public SuccessResponse<BoardResponse> getBoard(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.getById(id, userPubId));
    }

    @Operation(summary = "Create a board for an agentic team")
    @PostMapping("/")
    public SuccessResponse<BoardResponse> createBoard(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateBoardRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.create(userPubId, request));
    }

    @Operation(summary = "Get board tasks grouped by status")
    @GetMapping("/{boardId}/tasks/")
    public SuccessResponse<BoardTasksByStatusResponse> getTasksByStatus(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.getTasksByStatus(boardId, userPubId));
    }

    @Operation(summary = "Create a task on the board")
    @PostMapping("/{boardId}/tasks/")
    public SuccessResponse<BoardTaskResponse> createTask(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateBoardTaskRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.createTask(boardId, userPubId, request));
    }

    @Operation(summary = "Change task status")
    @PatchMapping("/tasks/{taskId}/status")
    public SuccessResponse<BoardTaskResponse> changeTaskStatus(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateBoardTaskStatusRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.changeTaskStatus(taskId, userPubId, request));
    }

    @Operation(summary = "Get comments for a task")
    @GetMapping("/tasks/{taskId}/comments/")
    public SuccessResponse<List<BoardTaskCommentResponse>> getComments(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID taskId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.getComments(taskId, userPubId));
    }

    @Operation(summary = "Create a comment on a task")
    @PostMapping("/tasks/{taskId}/comments/")
    public SuccessResponse<BoardTaskCommentResponse> createComment(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateBoardTaskCommentRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(boardService.createComment(taskId, userPubId, request));
    }
}
