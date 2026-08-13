package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelHandlerResponse;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelResponse;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelSessionMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelSessionResponse;
import ru.agimate.controlapi.controller.manage.dto.channel.CreateChannelRequest;
import ru.agimate.controlapi.controller.manage.dto.channel.UpdateChannelRequest;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.session.AgentSessionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageChannelController.PATH)
@RequiredArgsConstructor
@Tag(name = "Channels", description = "Manage dialog channels between triggers and reply tools")
public class ManageChannelController {

    public static final String PATH = "/manage/channels";

    private final ChannelService channelService;
    private final AgentSessionService agentSessionService;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;

    @Operation(summary = "List channels for the current user (optionally filtered by agent)")
    @GetMapping("/")
    public SuccessResponse<List<ChannelResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        var channels = agentId != null
                ? channelService.listForUserAndAgent(userId, agentId)
                : channelService.listForUser(userId);
        return SuccessResponse.ok(channelService.toResponses(channels));
    }

    @Operation(summary = "List available channel handlers and their config JSON Schema")
    @GetMapping("/handlers/")
    public SuccessResponse<List<ChannelHandlerResponse>> handlers() {
        return SuccessResponse.ok(channelService.listHandlers());
    }

    @Operation(summary = "Get channel by id")
    @GetMapping("/{id}")
    public SuccessResponse<ChannelResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        Channel channel = channelService.getById(userId, id);
        return SuccessResponse.ok(channelService.toResponse(channel));
    }

    @Operation(summary = "Create a channel")
    @PostMapping("/")
    public SuccessResponse<ChannelResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateChannelRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Channel channel = channelService.create(userId, new ChannelService.CreateChannelData(
                request.agentId(),
                request.name(),
                request.channelHandler(),
                request.connectorCode(),
                request.connectionId(),
                request.config(),
                request.inputFilter()
        ));
        return SuccessResponse.ok(channelService.toResponse(channel));
    }

    @Operation(summary = "Update a channel")
    @PatchMapping("/{id}")
    public SuccessResponse<ChannelResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateChannelRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Channel channel = channelService.update(userId, id, new ChannelService.UpdateChannelData(
                request.name(),
                request.config(),
                request.inputFilter(),
                Boolean.TRUE.equals(request.clearInputFilter())
        ));
        return SuccessResponse.ok(channelService.toResponse(channel));
    }

    @Operation(summary = "Soft delete a channel")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        channelService.delete(userId, id);
        return SuccessResponse.empty();
    }

    @Operation(summary = "List sessions of a channel")
    @GetMapping("/{id}/sessions/")
    public SuccessResponse<List<ChannelSessionResponse>> listSessions(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        Channel channel = channelService.getById(userId, id);
        List<ChannelSessionResponse> response = agentSessionService.listByChannelId(channel.getId()).stream()
                .map(ChannelSessionResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "List messages of a session")
    @GetMapping("/sessions/{sessionId}/messages/")
    public SuccessResponse<List<ChannelSessionMessageResponse>> listSessionMessages(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID sessionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        AgentSession session = agentSessionService.getById(sessionId);
        channelService.getByIdForUser(userId, session.getChannelId());
        List<ChannelSessionMessageResponse> response = channelSessionMessageRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .filter(m -> m.getMessage() != null)
                .map(ChannelSessionMessageResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Close a channel session")
    @PostMapping("/sessions/{sessionId}/close")
    public SuccessResponse<ChannelSessionResponse> closeSession(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID sessionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        AgentSession session = agentSessionService.getById(sessionId);
        channelService.getByIdForUser(userId, session.getChannelId());
        AgentSession closed = agentSessionService.close(sessionId);
        return SuccessResponse.ok(ChannelSessionResponse.from(closed));
    }
}
