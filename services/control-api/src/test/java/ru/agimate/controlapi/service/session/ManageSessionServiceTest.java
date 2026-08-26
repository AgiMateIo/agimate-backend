package ru.agimate.controlapi.service.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.manage.dto.session.SessionMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManageSessionService")
class ManageSessionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private AgentSessionService agentSessionService;
    @Mock
    private AgentRunQueryService agentRunQueryService;
    @Mock
    private WebchatMessageRepository webchatMessageRepository;
    @Mock
    private ChannelSessionMessageRepository channelSessionMessageRepository;
    @Mock
    private SignedFileUrlService signedFileUrlService;

    private ManageSessionService manageSessionService;

    private AgentSession session;

    @BeforeEach
    void setUp() {
        manageSessionService = new ManageSessionService(agentSessionService, agentRunQueryService,
                webchatMessageRepository, channelSessionMessageRepository, signedFileUrlService);
        session = AgentSession.builder()
                .id(SESSION_ID)
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .connectorCode("webchat")
                .channelId(CHANNEL_ID)
                .build();
    }

    private void stubList() {
        when(agentSessionService.list(USER_ID, null, null, null, 0, 50))
                .thenReturn(new PageImpl<>(List.of(session)));
    }

    @Nested
    @DisplayName("list — сессии владельца, обогащённые состоянием чата")
    class ListSessions {

        @Test
        @DisplayName("строка несёт бейдж, обрезанное превью и признак работающего агента")
        void enrichesRows() {
            stubList();
            when(webchatMessageRepository.countUnreadBySessionIds(List.of(SESSION_ID)))
                    .thenReturn(List.<Object[]>of(new Object[]{SESSION_ID, 3L}));
            when(webchatMessageRepository.findLastMessagesBySessionIds(List.of(SESSION_ID)))
                    .thenReturn(List.<Object[]>of(new Object[]{
                            SESSION_ID, "AGENT", "  готово  ", true,
                            Timestamp.valueOf(LocalDateTime.of(2026, 8, 15, 12, 0))}));
            when(agentRunQueryService.liveSessionIds(List.of(SESSION_ID))).thenReturn(Set.of(SESSION_ID));

            SessionResponse row = manageSessionService.list(USER_ID, null, null, null, 0, 50)
                    .getContent().get(0);

            assertEquals(SESSION_ID, row.id());
            assertEquals(AGENT_ID, row.agentId());
            assertEquals(3L, row.unreadCount());
            assertEquals("готово", row.lastMessage().text());
            assertEquals("AGENT", row.lastMessage().direction());
            assertTrue(row.lastMessage().hasAttachments());
            assertEquals(LocalDateTime.of(2026, 8, 15, 12, 0), row.lastMessage().createdAt());
            assertTrue(row.isRunning());
        }

        @Test
        @DisplayName("сессия без сообщений и без ранов — нули, а не null'ы")
        void emptySessionRow() {
            stubList();

            SessionResponse row = manageSessionService.list(USER_ID, null, null, null, 0, 50)
                    .getContent().get(0);

            assertEquals(0L, row.unreadCount());
            assertNull(row.lastMessage());
            assertFalse(row.isRunning());
        }

        @Test
        @DisplayName("пустая страница не идёт за обогащением")
        void emptyPageAsksNothing() {
            when(agentSessionService.list(USER_ID, null, null, null, 0, 50))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

            assertEquals(0, manageSessionService.list(USER_ID, null, null, null, 0, 50).getTotalElements());
            verifyNoInteractions(webchatMessageRepository, agentRunQueryService);
        }
    }

    @Nested
    @DisplayName("listMessages — историю отдаёт то хранилище, которым живёт канал")
    class ListMessages {

        @Test
        @DisplayName("вебчат — UI-лог с messageId и стримом")
        void readsWebchatLog() {
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            WebchatMessage message = WebchatMessage.builder()
                    .id(UUID.randomUUID())
                    .userId(USER_ID)
                    .messageId("m-1")
                    .direction(WebchatMessageDirection.AGENT)
                    .stream("answer")
                    .text("готово")
                    .build();
            when(webchatMessageRepository.findBySessionId(eq(SESSION_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(message)));

            SessionMessageResponse row = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0);

            assertEquals("m-1", row.messageId());
            assertEquals("AGENT", row.direction());
            assertEquals("answer", row.stream());
            verifyNoInteractions(channelSessionMessageRepository);
        }

        @Test
        @DisplayName("прочий коннектор — запись диалога, kind становится стримом")
        void readsChannelLog() {
            session.setConnectorCode("telegram");
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            ChannelSessionMessage message = ChannelSessionMessage.builder()
                    .id(UUID.randomUUID())
                    .kind(ChannelSessionMessageKind.ANSWER)
                    .message("готово")
                    .build();
            when(channelSessionMessageRepository.findWithMessageBySessionId(eq(SESSION_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(message)));

            SessionMessageResponse row = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0);

            assertNull(row.messageId());
            assertNull(row.parts());
            assertEquals("AGENT", row.direction());
            assertEquals("answer", row.stream());
            verify(webchatMessageRepository, never()).findBySessionId(any(), any());
        }

        @Test
        @DisplayName("входящее сообщение канала — направление USER без стрима")
        void inboundIsUser() {
            session.setConnectorCode("telegram");
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            ChannelSessionMessage message = ChannelSessionMessage.builder()
                    .id(UUID.randomUUID())
                    .kind(ChannelSessionMessageKind.INBOUND)
                    .message("привет")
                    .build();
            when(channelSessionMessageRepository.findWithMessageBySessionId(eq(SESSION_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(message)));

            SessionMessageResponse row = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0);

            assertEquals("USER", row.direction());
            assertNull(row.stream());
        }
    }

    @Nested
    @DisplayName("close — закрытие гасит бейдж")
    class Close {

        @Test
        @DisplayName("указатель уезжает на последнее сообщение, потом сессия закрывается")
        void closingMarksRead() {
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            when(agentSessionService.close(SESSION_ID)).thenReturn(session);

            manageSessionService.close(USER_ID, SESSION_ID);

            verify(agentSessionService).markReadThroughLatest(SESSION_ID);
            verify(agentSessionService).close(SESSION_ID);
        }
    }

    @Nested
    @DisplayName("владение — сессия чужого пользователя")
    class Ownership {

        @Test
        @DisplayName("чужая сессия — Forbidden, ничего не читаем и не пишем")
        void foreignSessionForbidden() {
            session.setUserId(UUID.randomUUID());
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);

            assertThrows(ForbiddenStatusException.class, () -> manageSessionService.get(USER_ID, SESSION_ID));
            assertThrows(ForbiddenStatusException.class,
                    () -> manageSessionService.rename(USER_ID, SESSION_ID, "чужое"));
            assertThrows(ForbiddenStatusException.class, () -> manageSessionService.close(USER_ID, SESSION_ID));
            verify(agentSessionService, never()).rename(any(), any());
            verify(agentSessionService, never()).close(any());
        }
    }
}
