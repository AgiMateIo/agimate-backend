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
            @RequestParam(required = false) UUID agentPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var channels = agentPubId != null
                ? channelService.listForUserAndAgent(userPubId, agentPubId)
                : channelService.listForUser(userPubId);
        return SuccessResponse.ok(channelService.toResponses(channels));
    }

    @Operation(summary = "Get channel by pubId")
    @GetMapping("/{pubId}")
    public SuccessResponse<ChannelResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.getByPubId(userPubId, pubId);
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
                request.agentPubId(),
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
    @PatchMapping("/{pubId}")
    public SuccessResponse<ChannelResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId,
            @Valid @RequestBody UpdateChannelRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.update(userPubId, pubId, new ChannelService.UpdateChannelData(
                request.name(),
                request.triggerMessageField(),
                request.replyToolParams(),
                request.inputFilter(),
                request.clearInputFilter()
        ));
        return SuccessResponse.ok(channelService.toResponse(channel));
    }

    @Operation(summary = "Soft delete a channel")
    @DeleteMapping("/{pubId}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        channelService.delete(userPubId, pubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "List sessions of a channel")
    @GetMapping("/{pubId}/sessions/")
    public SuccessResponse<List<ChannelSessionResponse>> listSessions(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        Channel channel = channelService.getByPubId(userPubId, pubId);
        List<ChannelSessionResponse> response = channelSessionService.listByChannelId(channel.getId()).stream()
                .map(ChannelSessionResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "List messages of a session")
    @GetMapping("/sessions/{sessionPubId}/messages/")
    public SuccessResponse<List<ChannelSessionMessageResponse>> listSessionMessages(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID sessionPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        ChannelSession session = channelSessionService.getByPubId(sessionPubId);
        channelService.getByIdForUser(userPubId, session.getChannelId());
        List<ChannelSessionMessageResponse> response = channelSessionMessageRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(ChannelSessionMessageResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Close a channel session")
    @PostMapping("/sessions/{sessionPubId}/close")
    public SuccessResponse<ChannelSessionResponse> closeSession(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID sessionPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        ChannelSession session = channelSessionService.getByPubId(sessionPubId);
        channelService.getByIdForUser(userPubId, session.getChannelId());
        ChannelSession closed = channelSessionService.close(sessionPubId);
        return SuccessResponse.ok(ChannelSessionResponse.from(closed));
    }
}
