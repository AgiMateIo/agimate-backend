package ru.agimate.controlapi.service.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestExchangeItem;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestExchangeKind;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestResponse;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestStatus;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRequestExchangeProjection;
import ru.agimate.controlapi.database.projections.AgentRequestProjection;
import ru.agimate.controlapi.database.projections.AgentRequestRunProjection;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.AgenticTeamService;
import ru.agimate.controlapi.service.subagent.SubagentService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRequestQueryService")
class AgentRequestQueryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID THREAD_ID = UUID.randomUUID();
    private static final UUID ASKER_ID = UUID.randomUUID();
    private static final UUID CALLEE_ID = UUID.randomUUID();
    private static final UUID ORIGIN_SESSION_ID = UUID.randomUUID();
    private static final String TOOL_COMPLETED = "tool_completed";

    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentRunQueryService agentRunQueryService;
    @Mock private AgenticTeamService agenticTeamService;

    private AgentRequestQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentRequestQueryService(agentSessionRepository, agentRunRepository,
                agentRepository, agentRunQueryService, agenticTeamService);
        lenient().when(agentRepository.findAllById(anyCollection())).thenReturn(List.of(
                Agent.builder().id(ASKER_ID).name("Менеджер").build(),
                Agent.builder().id(CALLEE_ID).name("Юрист").build()));
    }

    /** Проекции — интерфейсы, и в тестах их проще подставить вручную, чем стабить по геттеру. */
    private static final class ThreadRow implements AgentRequestProjection {
        private UUID teamId = TEAM_ID;
        private LocalDateTime createdAt = LocalDateTime.now().minusMinutes(20);

        ThreadRow teamId(UUID value) {
            this.teamId = value;
            return this;
        }

        ThreadRow createdAt(LocalDateTime value) {
            this.createdAt = value;
            return this;
        }

        @Override public UUID getId() { return THREAD_ID; }
        @Override public UUID getUserId() { return USER_ID; }
        @Override public UUID getTeamId() { return teamId; }
        @Override public String getTitle() { return "Договор с ООО «Ромашка»"; }
        @Override public UUID getToAgentId() { return CALLEE_ID; }
        @Override public UUID getFromAgentId() { return ASKER_ID; }
        @Override public UUID getOriginSessionId() { return ORIGIN_SESSION_ID; }
        @Override public String getOriginTitle() { return "Клиент Ромашка"; }
        @Override public String getOriginConnectorCode() { return "telegram"; }
        @Override public LocalDateTime getCreatedAt() { return createdAt; }
        @Override public LocalDateTime getLastActivityAt() { return LocalDateTime.now().minusMinutes(5); }
        @Override public LocalDateTime getClosedAt() { return null; }
    }

    private static final class RunRow implements AgentRequestExchangeProjection {
        private final UUID id = UUID.randomUUID();
        private String name = SubagentService.REQUEST_TRIGGER;
        private RunStatus status = RunStatus.DONE;
        private LocalDateTime steeredAt;
        private LocalDateTime cancelRequestedAt;
        private LocalDateTime reportedAt;
        private LocalDateTime createdAt = LocalDateTime.now().minusMinutes(10);
        private LocalDateTime lastActivityAt = LocalDateTime.now().minusMinutes(5);
        private String result;
        private String error;
        private Map<String, Object> input;

        RunRow name(String value) {
            this.name = value;
            return this;
        }

        RunRow status(RunStatus value) {
            this.status = value;
            return this;
        }

        RunRow steered(LocalDateTime value) {
            this.steeredAt = value;
            this.status = RunStatus.STEERED;
            return this;
        }

        RunRow cancelRequestedAt(LocalDateTime value) {
            this.cancelRequestedAt = value;
            return this;
        }

        RunRow reportedAt(LocalDateTime value) {
            this.reportedAt = value;
            return this;
        }

        RunRow at(LocalDateTime created, LocalDateTime finished) {
            this.createdAt = created;
            this.lastActivityAt = finished;
            return this;
        }

        RunRow result(String value) {
            this.result = value;
            return this;
        }

        RunRow error(String value) {
            this.error = value;
            this.status = RunStatus.FAILED;
            return this;
        }

        RunRow input(Map<String, Object> value) {
            this.input = value;
            return this;
        }

        @Override public UUID getSessionId() { return THREAD_ID; }
        @Override public UUID getId() { return id; }
        @Override public String getName() { return name; }
        @Override public RunStatus getStatus() { return status; }
        @Override public LocalDateTime getSteeredAt() { return steeredAt; }
        @Override public LocalDateTime getCancelRequestedAt() { return cancelRequestedAt; }
        @Override public LocalDateTime getReportedAt() { return reportedAt; }
        @Override public LocalDateTime getCreatedAt() { return createdAt; }
        @Override public LocalDateTime getLastActivityAt() { return lastActivityAt; }
        @Override public LocalDateTime getUpdatedAt() { return lastActivityAt; }
        @Override public String getResult() { return result; }
        @Override public String getError() { return error; }
        @Override public Map<String, Object> getInput() { return input; }
    }

    private static ThreadRow thread() {
        return new ThreadRow();
    }

    /** Отвечающий ран: поручение, на которое агент ответил — самый частый случай в ветке. */
    private static RunRow answered(String text) {
        return new RunRow().result(text);
    }

    /** The listing as the service assembles it: one thread, the runs and liveness stubbed by the test. */
    private AgentRequestResponse row(AgentRequestProjection thread, List<RunRow> runs, boolean live) {
        when(agentSessionRepository.findRequests(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(thread)));
        when(agentRunRepository.findRequestRuns(anyCollection()))
                .thenReturn(runs.stream().map(AgentRequestRunProjection.class::cast).toList());
        when(agentRunQueryService.liveSessionIds(anyCollection()))
                .thenReturn(live ? Set.of(THREAD_ID) : Set.of());
        Page<AgentRequestResponse> page = service.list(USER_ID, TEAM_ID, null, null, null, null, 0, 50);
        return page.getContent().getFirst();
    }

    @Nested
    @DisplayName("состояние ветки")
    class Status {

        @Test
        @DisplayName("живой ран — в работе; имена сторон подставлены")
        void working() {
            AgentRequestResponse request = row(thread(),
                    List.of(new RunRow().status(RunStatus.RUNNING)), true);

            assertEquals(AgentRequestStatus.WORKING, request.status());
            assertEquals("Менеджер", request.from().agentName());
            assertEquals("Юрист", request.to().agentName());
            assertEquals(1, request.requestsCount());
        }

        @Test
        @DisplayName("остановленный ран сильнее живого: пользователю не показывают работу, которую он прервал")
        void cancelledBeatsLive() {
            AgentRequestResponse request = row(thread(), List.of(new RunRow()
                    .status(RunStatus.RUNNING).cancelRequestedAt(LocalDateTime.now())), true);

            assertEquals(AgentRequestStatus.CANCELLED, request.status());
        }

        @Test
        @DisplayName("промежуточный ответ при живой сессии — всё ещё в работе, но отчёт уже виден")
        void interimAnswerStaysWorking() {
            AgentRequestResponse request = row(thread(), List.of(answered("почти готово")), true);

            assertEquals(AgentRequestStatus.WORKING, request.status());
            assertEquals("почти готово", request.lastReport().preview());
            assertNull(request.lastReport().reportedAt());
        }

        @Test
        @DisplayName("ошибка ветки — FAILED, текст ошибки идёт превью")
        void failed() {
            AgentRequestResponse request = row(thread(),
                    List.of(new RunRow().error("нет доступа к файлу")), false);

            assertEquals(AgentRequestStatus.FAILED, request.status());
            assertEquals(AgentRequestStatus.FAILED, request.lastReport().status());
            assertEquals("нет доступа к файлу", request.lastReport().preview());
        }

        @Test
        @DisplayName("поручение без ранов: только что заведённое — в работе, старое — не запустилось")
        void stalled() {
            LocalDateTime now = LocalDateTime.now();
            assertEquals(AgentRequestStatus.WORKING,
                    row(thread().createdAt(now), List.of(), false).status());
            assertEquals(AgentRequestStatus.STALLED,
                    row(thread().createdAt(now.minusDays(1)), List.of(), false).status());
        }

        @Test
        @DisplayName("ран, застрявший в очереди, не выдаётся за работу")
        void stuckInQueue() {
            AgentRequestResponse request = row(thread(),
                    List.of(new RunRow().status(RunStatus.ENQUEUED)), false);

            assertEquals(AgentRequestStatus.STALLED, request.status());
        }
    }

    @Nested
    @DisplayName("свёртка ранов")
    class Fold {

        @Test
        @DisplayName("поглощённый ран не считается последним, но поручением — считается")
        void absorbedRunIsStillARequest() {
            LocalDateTime now = LocalDateTime.now();
            AgentRequestResponse request = row(thread(), List.of(
                    answered("первый ответ")
                            .at(now.minusMinutes(10), now.minusMinutes(9))
                            .reportedAt(now.minusMinutes(9)),
                    new RunRow().at(now.minusMinutes(8), now.minusMinutes(8))
                            .steered(now.minusMinutes(8))), false);

            assertEquals(2, request.requestsCount());
            assertEquals(AgentRequestStatus.DONE, request.status());
            assertEquals("первый ответ", request.lastReport().preview());
        }

        @Test
        @DisplayName("отчёт после отложенной тулы: ран tool_completed отвечает, но поручением не считается")
        void toolCompletedRunCarriesTheReport() {
            LocalDateTime now = LocalDateTime.now();
            AgentRequestResponse request = row(thread(), List.of(
                    answered("картинка рисуется").at(now.minusMinutes(10), now.minusMinutes(9)),
                    answered("готово").name(TOOL_COMPLETED)
                            .at(now.minusMinutes(6), now.minusMinutes(5))
                            .reportedAt(now.minusMinutes(5))), false);

            assertEquals(1, request.requestsCount());
            assertEquals("готово", request.lastReport().preview());
            assertEquals(now.minusMinutes(5).withNano(0),
                    request.lastReport().reportedAt().withNano(0));
        }

        @Test
        @DisplayName("длинный отчёт обрезается для списка")
        void previewIsCut() {
            String report = "я".repeat(AgentRequestQueryService.PREVIEW_LENGTH + 50);
            AgentRequestResponse request = row(thread(), List.of(answered(report)), false);

            assertEquals(AgentRequestQueryService.PREVIEW_LENGTH + 1, request.lastReport().preview().length());
            assertTrue(request.lastReport().preview().endsWith("…"));
        }
    }

    @Nested
    @DisplayName("ветка целиком")
    class Detail {

        @Test
        @DisplayName("поручения и отчёты идут по времени, у поручения — текст из данных триггера")
        void exchangeInTimeOrder() {
            LocalDateTime now = LocalDateTime.now();
            when(agentSessionRepository.findRequests(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(thread())));
            when(agentRunQueryService.liveSessionIds(anyCollection())).thenReturn(Set.of());
            when(agentRunRepository.findExchangeRuns(THREAD_ID)).thenReturn(List.of(
                    answered("пункт 4.2 без потолка")
                            .at(now.minusMinutes(10), now.minusMinutes(8))
                            .input(Map.of("mode", "new", "title", "Договор",
                                    "instructions", "проверь 4.2", "context", "клиент торопится")),
                    answered("формулировка готова")
                            .at(now.minusMinutes(6), now.minusMinutes(4))
                            .input(Map.of("mode", "append", "title", "Договор",
                                    "instructions", "предложи формулировку"))));

            AgentRequestDetailResponse detail = service.get(USER_ID, TEAM_ID, THREAD_ID);

            List<AgentRequestExchangeItem> items = detail.exchange();
            assertEquals(4, items.size());
            assertEquals(List.of(AgentRequestExchangeKind.REQUEST, AgentRequestExchangeKind.REPORT,
                            AgentRequestExchangeKind.REQUEST, AgentRequestExchangeKind.REPORT),
                    items.stream().map(AgentRequestExchangeItem::kind).toList());
            assertEquals("проверь 4.2", items.getFirst().instructions());
            assertEquals("клиент торопится", items.getFirst().context());
            assertEquals("new", items.getFirst().mode());
            assertNull(items.get(2).context());
            assertEquals("пункт 4.2 без потолка", items.get(1).text());
        }

        @Test
        @DisplayName("команда проверяется до чтения — чужая упирается в её же 404")
        void teamIsTheGate() {
            when(agentSessionRepository.findRequests(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(thread())));
            when(agentRunQueryService.liveSessionIds(anyCollection())).thenReturn(Set.of());
            when(agentRunRepository.findExchangeRuns(THREAD_ID)).thenReturn(List.of());

            service.get(USER_ID, TEAM_ID, THREAD_ID);

            verify(agenticTeamService).getById(TEAM_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("событие")
    class Event {

        @Test
        @DisplayName("агент вне команды: строка есть, события нет — адресовать его некуда")
        void teamlessCalleeIsNotPublished() {
            when(agentSessionRepository.findById(THREAD_ID))
                    .thenReturn(Optional.of(AgentSession.builder().userId(USER_ID).build()));
            when(agentSessionRepository.findRequests(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(thread().teamId(null))));

            assertTrue(service.forEvent(THREAD_ID).isEmpty());
        }

        @Test
        @DisplayName("ветки нет — публиковать нечего")
        void missingThread() {
            when(agentSessionRepository.findById(THREAD_ID)).thenReturn(Optional.empty());

            assertTrue(service.forEvent(THREAD_ID).isEmpty());
        }
    }

    @Test
    @DisplayName("пустая страница не ходит за ранами и именами")
    void emptyPageReadsNothing() {
        when(agentSessionRepository.findRequests(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        assertTrue(service.list(USER_ID, TEAM_ID, null, null, null, null, 0, 50).isEmpty());
        verify(agentRunRepository, never()).findRequestRuns(anyCollection());
    }
}
