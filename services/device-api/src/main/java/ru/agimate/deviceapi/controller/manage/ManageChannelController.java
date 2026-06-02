package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.channel.ChannelResponse;
import ru.agimate.deviceapi.controller.manage.dto.channel.ChannelSessionMessageResponse;
import ru.agimate.deviceapi.controller.manage.dto.channel.ChannelSessionResponse;
import ru.agimate.deviceapi.controller.manage.dto.channel.CreateChannelRequest;
import ru.agimate.deviceapi.controller.manage.dto.channel.UpdateChannelRequest;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.ChannelSession;
import ru.agimate.deviceapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.deviceapi.service.channel.ChannelService;
import ru.agimate.deviceapi.service.channel.ChannelSessionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageChannelController.PATH)
@RequiredArgsConstructor
@Tag(name = "Channels", description = "Manage dialog channels between triggers and reply tools")
public class ManageChannelController {

    public static final String PATH = "/manage/channels";

    private final ChannelService channelService;
    private final ChannelSessionService channelSessionService;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;

    @Operation(summary = "List channels for the current user (optionally filtered by agent)")
    @GetMapping("/")
    public SuccessResponse<List<ChannelResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var channels = agentId != null
                ? channelService.listForUserAndAgent(userPubId, agentId)
                : channelService.listForUser(userPubId);
        return SuccessResponse.ok(channelService.toResponses(channels));
    }

    @Operation(summary = "Get channel by id")
    @GetMapping("/{id}")
    public SuccessResponse<ChannelResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.getById(userPubId, id);
        return SuccessResponse.ok(channelService.toResponse(channel));
    }

    @Operation(summary = "Create a channel")
    @PostMapping("/")
    public SuccessResponse<ChannelResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateChannelRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.create(userPubId, new ChannelService.CreateChannelData(
                request.agentId(),
                request.name(),
                request.triggerConnectorCode(),
                request.triggerIdentity(),
                request.triggerName(),
                request.triggerMessageField(),
                request.replyConnectorCode(),
                request.replyIdentity(),
                request.replyToolName(),
                request.replyToolParams(),
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
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.update(userPubId, id, new ChannelService.UpdateChannelData(
                request.name(),
                request.triggerMessageField(),
                request.replyToolParams(),
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
        UUID userPubId = UUID.fromString(principal.pubId());
        channelService.delete(userPubId, id);
        return SuccessResponse.empty();
    }

    @Operation(summary = "List sessions of a channel")
    @GetMapping("/{id}/sessions/")
    public SuccessResponse<List<ChannelSessionResponse>> listSessions(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.getById(userPubId, id);
        List<ChannelSessionResponse> response = channelSessionService.listByChannelId(channel.getId()).stream()
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
        UUID userPubId = UUID.fromString(principal.pubId());
        ChannelSession session = channelSessionService.getById(sessionId);
        channelService.getByIdForUser(userPubId, session.getChannelId());
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
        UUID userPubId = UUID.fromString(principal.pubId());
        ChannelSession session = channelSessionService.getById(sessionId);
        channelService.getByIdForUser(userPubId, session.getChannelId());
        ChannelSession closed = channelSessionService.close(sessionId);
        return SuccessResponse.ok(ChannelSessionResponse.from(closed));
    }
}
