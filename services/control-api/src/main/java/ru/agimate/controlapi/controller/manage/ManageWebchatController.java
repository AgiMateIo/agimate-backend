package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatContactResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendMessageRequest;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatStartSessionRequest;
import ru.agimate.controlapi.service.webchat.WebchatService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageWebchatController.PATH)
@RequiredArgsConstructor
@Tag(name = "Webchat", description = "The web chat transport: starting a chat, sending into it, "
        + "its live channel. The conversation itself lives under /manage/sessions")
public class ManageWebchatController {

    public static final String PATH = "/manage/webchat";

    private final WebchatService webchatService;

    @Operation(summary = "Start a new chat session with an agent",
            description = "Lazily materializes the user's webchat connection, the agent binding and the channel")
    @PostMapping("/sessions")
    public SuccessResponse<SessionResponse> startSession(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody WebchatStartSessionRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(webchatService.startSession(userId, request.agentId()));
    }

    @Operation(summary = "Send a message into a session",
            description = "Persists the message, echoes it to Centrifugo and routes it to the agent; "
                    + "the reply arrives as webchat_message events on webchat:{sessionId}")
    @PostMapping("/sessions/{id}/messages")
    public SuccessResponse<WebchatSendResponse> sendMessage(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody WebchatSendMessageRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(webchatService.send(userId, id, request));
    }

    @Operation(summary = "List agents as chat contacts, freshest conversation first",
            description = "One row per agent: the agent plus unread count, last message preview and "
                    + "whether it is working right now")
    @GetMapping("/contacts/")
    public SuccessResponse<PageResponse<WebchatContactResponse>> listContacts(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(webchatService.listContacts(userId, page, size)));
    }

    @Operation(summary = "Get Centrifugo tokens for the session channel webchat:{sessionId}")
    @PostMapping("/sessions/{id}/token")
    public SuccessResponse<CentrifugoTokenResponse> token(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(webchatService.token(userId, id));
    }
}
