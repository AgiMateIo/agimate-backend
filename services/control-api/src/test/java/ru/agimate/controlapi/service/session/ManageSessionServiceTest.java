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
import ru.agimate.controlapi.controller.manage.dto.session.ToolCallDescriptor;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.channel.handler.ChannelMessageLog;
import ru.agimate.controlapi.service.channel.handler.ChannelMessageLogResolver;
import ru.agimate.controlapi.service.view.SessionToolCallService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.times;
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
    private AgentSessionRepository agentSessionRepository;
    @Mock
    private ChannelSessionMessageRepository channelSessionMessageRepository;
    @Mock
    private ChannelMessageLogResolver channelMessageLogResolver;
    @Mock
    private ChannelMessageLog channelMessageLog;
    @Mock
    private SessionToolCallService sessionToolCallService;

    private ManageSessionService manageSessionService;

    private AgentSession session;

    @BeforeEach
    void setUp() {
        SessionRows sessionRows = new SessionRows(agentSessionRepository, webchatMessageRepository, agentRunQueryService);
        manageSessionService = new ManageSessionService(agentSessionService, sessionRows,
                channelSessionMessageRepository, channelMessageLogResolver, sessionToolCallService);
        session = AgentSession.builder()
                .id(SESSION_ID)
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .connectorCode("webchat")
                .channelId(CHANNEL_ID)
                .build();
    }

    private void stubList() {
        when(agentSessionService.list(USER_ID, null, null, null, null, null, null, 0, 50))
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

            SessionResponse row = manageSessionService.list(USER_ID, null, null, null, null, 0, 50)
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

            SessionResponse row = manageSessionService.list(USER_ID, null, null, null, null, 0, 50)
                    .getContent().get(0);

            assertEquals(0L, row.unreadCount());
            assertNull(row.lastMessage());
            assertFalse(row.isRunning());
        }

        @Test
        @DisplayName("пустая страница не идёт за обогащением")
        void emptyPageAsksNothing() {
            when(agentSessionService.list(USER_ID, null, null, null, null, null, null, 0, 50))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

            assertEquals(0, manageSessionService.list(USER_ID, null, null, null, null, 0, 50).getTotalElements());
            verifyNoInteractions(webchatMessageRepository, agentRunQueryService);
        }
    }

    @Nested
    @DisplayName("listMessages — историю отдаёт то хранилище, которым живёт канал")
    class ListMessages {

        @Test
        @DisplayName("канал со своим логом — историю отдаёт плагин (вебчат: messageId и стрим)")
        void readsOwnLog() {
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            when(channelMessageLogResolver.find(session)).thenReturn(Optional.of(channelMessageLog));
            SessionMessageResponse expected = new SessionMessageResponse(
                    UUID.randomUUID(), "m-1", "AGENT", "answer", "готово", null, null, null, LocalDateTime.now());
            when(channelMessageLog.listMessages(USER_ID, SESSION_ID, 0, 50))
                    .thenReturn(new PageImpl<>(List.of(expected)));

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
    @DisplayName("listMessages — дескрипторы вызовов приходят вместе с сообщениями")
    class ToolCalls {

        private static final ToolCallDescriptor PLAIN =
                new ToolCallDescriptor("c1", "get_memory", "conn-1", null);
        private static final ToolCallDescriptor VIEW =
                new ToolCallDescriptor("c2", "show_memory", "conn-2", "ui://persist-memory/memory");

        private SessionMessageResponse row(List<String> ids) {
            return new SessionMessageResponse(UUID.randomUUID(), "m-1", "AGENT", "progress",
                    "🔧", null, ids, null, LocalDateTime.now());
        }

        private void webchat(SessionMessageResponse... rows) {
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            when(channelMessageLogResolver.find(session)).thenReturn(Optional.of(channelMessageLog));
            when(channelMessageLog.listMessages(USER_ID, SESSION_ID, 0, 50))
                    .thenReturn(new PageImpl<>(List.of(rows)));
        }

        @Test
        @DisplayName("ветвь вебчата: дескрипторы приходят вместе с сообщениями")
        void webchatDecorated() {
            webchat(row(List.of("c1")));
            when(sessionToolCallService.describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c1")))
                    .thenReturn(Optional.of(Map.of("c1", PLAIN)));

            SessionMessageResponse out = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0);

            assertEquals(List.of(PLAIN), out.toolCalls());
        }

        @Test
        @DisplayName("общая проекция: дескрипторы приходят так же")
        void channelLogDecorated() {
            session.setConnectorCode("telegram");
            when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
            when(channelSessionMessageRepository.findWithMessageBySessionId(eq(SESSION_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(ChannelSessionMessage.builder()
                            .id(UUID.randomUUID())
                            .kind(ChannelSessionMessageKind.PROGRESS)
                            .message("🔧")
                            .toolCallIds(List.of("c2"))
                            .build())));
            when(sessionToolCallService.describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c2")))
                    .thenReturn(Optional.of(Map.of("c2", VIEW)));

            SessionMessageResponse out = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0);

            assertEquals(List.of(VIEW), out.toolCalls());
        }

        @Test
        @DisplayName("id без записи в журнале опускается: массив короче toolCallIds, порядок сохранён")
        void missingIdsOmitted() {
            webchat(row(List.of("c1", "c2")));
            when(sessionToolCallService.describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c1", "c2")))
                    .thenReturn(Optional.of(Map.of("c2", VIEW)));

            SessionMessageResponse out = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0);

            assertEquals(List.of(VIEW), out.toolCalls());
        }

        @Test
        @DisplayName("ни одного id в журнале — пустой массив, а не null: запросов не нужно")
        void noneFoundIsEmptyArray() {
            webchat(row(List.of("c1")));
            when(sessionToolCallService.describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c1")))
                    .thenReturn(Optional.of(Map.of()));

            assertEquals(List.of(), manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0).toolCalls());
        }

        @Test
        @DisplayName("резолв не удался — toolCalls = null, история отдаётся как есть")
        void resolutionFailureLeavesNull() {
            webchat(row(List.of("c1")));
            when(sessionToolCallService.describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c1")))
                    .thenReturn(Optional.empty());

            assertNull(manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0).toolCalls());
        }

        @Test
        @DisplayName("страница без toolCallIds не трогает резолвер")
        void noIdsAskNothing() {
            webchat(row(null));

            assertNull(manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent().get(0).toolCalls());
            verifyNoInteractions(sessionToolCallService);
        }

        @Test
        @DisplayName("резолв один на страницу: id разных строк уходят одним запросом")
        void oneDescribePerPage() {
            webchat(row(List.of("c1")), row(List.of("c2")));
            when(sessionToolCallService.describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c1", "c2")))
                    .thenReturn(Optional.of(Map.of("c1", PLAIN, "c2", VIEW)));

            List<SessionMessageResponse> rows = manageSessionService
                    .listMessages(USER_ID, SESSION_ID, 0, 50).getContent();

            assertEquals(List.of(PLAIN), rows.get(0).toolCalls());
            assertEquals(List.of(VIEW), rows.get(1).toolCalls());
            verify(sessionToolCallService, times(1))
                    .describe(USER_ID, AGENT_ID, SESSION_ID, List.of("c1", "c2"));
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
