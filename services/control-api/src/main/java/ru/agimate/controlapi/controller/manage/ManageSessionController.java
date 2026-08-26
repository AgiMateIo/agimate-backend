package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.session.MarkSessionReadRequest;
import ru.agimate.controlapi.controller.manage.dto.session.SessionMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.controller.manage.dto.session.UpdateSessionRequest;
import ru.agimate.controlapi.service.session.ManageSessionService;

import java.util.UUID;

/**
 * Sessions of every channel under one resource. A conversation is the same thing whether it runs in
 * the web chat or in a messenger — only the transport differs, and that lives in {@code /manage/webchat}.
 */
@RestController
@RequestMapping(ManageSessionController.PATH)
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Conversations with agents, whatever channel carries them")
public class ManageSessionController {

    public static final String PATH = "/manage/sessions";

    private final ManageSessionService manageSessionService;

    @Operation(summary = "List sessions, freshest activity first")
    @GetMapping("/")
    public SuccessResponse<PageResponse<SessionResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID channelId,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(
                manageSessionService.list(userId, agentId, channelId, connectorCode, page, size)));
    }

    @Operation(summary = "Get a session by id")
    @GetMapping("/{id}")
    public SuccessResponse<SessionResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(manageSessionService.get(userId, id));
    }

    @Operation(summary = "Rename a session",
            description = "Overrides the title derived from the first message")
    @PatchMapping("/{id}")
    public SuccessResponse<SessionResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSessionRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(manageSessionService.rename(userId, id, request.title()));
    }

    @Operation(summary = "Close a session",
            description = "The history stays; the conversation stops asking for attention in the listings")
    @PostMapping("/{id}/close")
    public SuccessResponse<SessionResponse> close(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(manageSessionService.close(userId, id));
    }

    @Operation(summary = "Session message history, newest first",
            description = "Page 0 is the freshest; the frontend reverses it when rendering")
    @GetMapping("/{id}/messages/")
    public SuccessResponse<PageResponse<SessionMessageResponse>> listMessages(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(manageSessionService.listMessages(userId, id, page, size)));
    }

    @Operation(summary = "Mark a session read",
            description = "Up to lastReadMessageId (the row id from the history), or up to the end of "
                    + "the session when the body names none; the pointer never moves backwards")
    @PostMapping("/{id}/read")
    public SuccessResponse<Void> markRead(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) MarkSessionReadRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        manageSessionService.markRead(userId, id, request != null ? request.lastReadMessageId() : null);
        return SuccessResponse.empty();
    }
}
