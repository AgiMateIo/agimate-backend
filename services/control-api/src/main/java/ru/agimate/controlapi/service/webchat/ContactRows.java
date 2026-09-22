package ru.agimate.controlapi.service.webchat;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.manage.dto.session.SessionLastMessage;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatContactResponse;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;
import ru.agimate.controlapi.service.session.SessionRows;
import ru.agimate.controlapi.util.SqlValues;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The agent as a row of the messenger's contact list, for {@code /manage/webchat/contacts/} and for
 * its live event alike. Reads only, from repositories — see {@link SessionRows} for why.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContactRows {

    private final AgentRepository agentRepository;
    private final WebchatMessageRepository webchatMessageRepository;
    private final AgentRunQueryService agentRunQueryService;

    /**
     * The user's agents ordered by the freshness of their chat. The ordering is the reason this is one
     * query and not a merge of two listings — see {@code AgentRepository.findChatContacts}.
     */
    public Page<WebchatContactResponse> page(UUID userId, int page, int size) {
        Page<Object[]> rows = agentRepository.findChatContacts(
                userId, WebchatChannelHandler.CONNECTOR_CODE, PageRequest.of(page, size));
        return rows.map(mapper(rows.getContent()));
    }

    /** Empty for a deleted agent. */
    public Optional<WebchatContactResponse> find(UUID agentId) {
        List<Object[]> rows = agentRepository.findChatContact(agentId, WebchatChannelHandler.CONNECTOR_CODE);
        return rows.stream().findFirst().map(mapper(rows));
    }

    /** Three batch queries (unread, preview, «working now») for a page of contact rows. */
    private Function<Object[], WebchatContactResponse> mapper(List<Object[]> rows) {
        List<UUID> agentIds = rows.stream().map(row -> (UUID) row[0]).toList();
        Map<UUID, Long> unread = unreadByAgent(agentIds);
        Map<UUID, ContactPreview> previews = lastMessagesByAgent(agentIds);
        Set<UUID> live = agentRunQueryService.liveAgentIds(agentIds, WebchatChannelHandler.CONNECTOR_CODE);

        return row -> {
            UUID id = (UUID) row[0];
            ContactPreview preview = previews.get(id);
            return new WebchatContactResponse(
                    id,
                    (String) row[1],
                    (String) row[2],
                    Boolean.TRUE.equals(row[3]),
                    unread.getOrDefault(id, 0L),
                    preview != null ? preview.message() : null,
                    preview != null ? preview.sessionId() : null,
                    SqlValues.localDateTime(row[4]),
                    live.contains(id));
        };
    }

    private Map<UUID, Long> unreadByAgent(List<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        return webchatMessageRepository.countUnreadByAgentIds(agentIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
    }

    private Map<UUID, ContactPreview> lastMessagesByAgent(List<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        return webchatMessageRepository.findLastMessagesByAgentIds(agentIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> new ContactPreview((UUID) row[1],
                                SessionRows.lastMessage(row[2], row[3], row[4], row[5]))));
    }

    /** The preview of a contact row together with the conversation it came from. */
    private record ContactPreview(UUID sessionId, SessionLastMessage message) {}
}
