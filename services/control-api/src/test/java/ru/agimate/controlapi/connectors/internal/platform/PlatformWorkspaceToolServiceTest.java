package ru.agimate.controlapi.connectors.internal.platform;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;
import ru.agimate.controlapi.service.AgenticTeamService;
import ru.agimate.controlapi.service.ConnectorJobManageService;
import ru.agimate.controlapi.service.board.BoardService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlatformWorkspaceToolService")
class PlatformWorkspaceToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SELF_AGENT_ID = UUID.randomUUID();

    private final AgenticTeamRepository agenticTeamRepository = mock(AgenticTeamRepository.class);
    private final AgentPresetRepository agentPresetRepository = mock(AgentPresetRepository.class);
    private final BoardRepository boardRepository = mock(BoardRepository.class);
    private final ConnectorJobRepository connectorJobRepository = mock(ConnectorJobRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgenticTeamService agenticTeamService = mock(AgenticTeamService.class);
    private final BoardService boardService = mock(BoardService.class);
    private final ConnectorJobManageService connectorJobManageService = mock(ConnectorJobManageService.class);

    private final PlatformWorkspaceToolService workspaceTools = new PlatformWorkspaceToolService(
            agenticTeamRepository, agentPresetRepository, boardRepository, connectorJobRepository, agentRepository,
            agenticTeamService, boardService, connectorJobManageService);
    private final PlatformConnectorService handler = new PlatformConnectorService(
            mock(PlatformAgentToolService.class), mock(PlatformConnectionToolService.class),
            mock(PlatformLlmToolService.class), workspaceTools, mock(PlatformObservabilityToolService.class));

    /** Инициатор вызова = SELF_AGENT_ID (владелец USER_ID). */
    private static ConnectorEnv selfEnv() {
        return new ConnectorEnv(null, USER_ID, SELF_AGENT_ID, null, null, null, Map.of(), null);
    }

    // ---- teams -----------------------------------------------------------------------------

    @Test
    @DisplayName("create_team уходит в новый overload сервиса с примитивными аргументами")
    void createTeamCallsTheNewOverload() {
        UUID teamId = UUID.randomUUID();
        AgenticTeam team = AgenticTeam.builder()
                .id(teamId).userId(USER_ID).name("My Team").description("desc").build();
        when(agenticTeamService.create(eq(USER_ID), eq("My Team"), eq("desc"))).thenReturn(team);
        when(agenticTeamRepository.findById(teamId)).thenReturn(Optional.of(team));
        when(agentRepository.findByUserIdAndAgenticTeamId(USER_ID, teamId)).thenReturn(List.of());

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "create_team",
                Map.of("name", "My Team", "description", "desc"));

        // Именно трёхаргументный overload — не controller-DTO create.
        verify(agenticTeamService).create(USER_ID, "My Team", "desc");
        assertEquals("My Team", result.get("name"));
        assertEquals("desc", result.get("description"));
        assertEquals(List.of(), result.get("members"));
    }

    @Test
    @DisplayName("get_team возвращает состав команды (AgentBrief)")
    void getTeamReturnsMembers() {
        UUID teamId = UUID.randomUUID();
        AgenticTeam team = AgenticTeam.builder().id(teamId).userId(USER_ID).name("Team").build();
        when(agenticTeamRepository.findById(teamId)).thenReturn(Optional.of(team));
        Agent member = Agent.builder().id(UUID.randomUUID()).userId(USER_ID).name("Bot")
                .type(AgentType.GENERIC).agenticTeamId(teamId).build();
        when(agentRepository.findByUserIdAndAgenticTeamId(USER_ID, teamId)).thenReturn(List.of(member));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "get_team",
                Map.of("teamId", teamId.toString()));

        List<?> members = (List<?>) result.get("members");
        assertEquals(1, members.size());
        assertEquals("Bot", ((Map<?, ?>) members.getFirst()).get("name"));
    }

    @Test
    @DisplayName("delete_team с доской у команды — отказ сервиса переведён в ConnectorException")
    void deleteTeamWithBoardIsTranslated() {
        UUID teamId = UUID.randomUUID();
        doThrow(new BadRequestStatusException("Cannot delete team that has a board"))
                .when(agenticTeamService).delete(teamId, USER_ID);

        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(
                selfEnv(), "delete_team", Map.of("teamId", teamId.toString())));

        assertTrue(ex.getMessage().contains("board"));
        verify(agenticTeamService).delete(teamId, USER_ID);
    }

    // ---- jobs ------------------------------------------------------------------------------

    @Test
    @DisplayName("delete_job над SYSTEM-джобой — отказ сервиса переведён")
    void deleteSystemJobIsTranslated() {
        UUID jobId = UUID.randomUUID();
        doThrow(new BadRequestStatusException(
                "Declarative connector task cannot be deleted: pause it or delete the integration"))
                .when(connectorJobManageService).delete(jobId, USER_ID);

        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(
                selfEnv(), "delete_job", Map.of("jobId", jobId.toString())));

        assertTrue(ex.getMessage().contains("cannot be deleted"));
        verify(connectorJobManageService).delete(jobId, USER_ID);
    }

    @Test
    @DisplayName("run_job_now над приостановленной джобой — отказ сервиса переведён")
    void runJobNowPausedIsTranslated() {
        UUID jobId = UUID.randomUUID();
        doThrow(new BadRequestStatusException("Job is paused: resume it first"))
                .when(connectorJobManageService).runNow(jobId, USER_ID);

        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(
                selfEnv(), "run_job_now", Map.of("jobId", jobId.toString())));

        assertTrue(ex.getMessage().contains("paused"));
        verify(connectorJobManageService).runNow(jobId, USER_ID);
    }

    @Test
    @DisplayName("list_connector_jobs собирает спецификацию по connectorCode/kind/connectionId и включает SYSTEM")
    void listConnectorJobsBuildsTheSpec() {
        UUID connectionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ConnectorJob job = ConnectorJob.builder()
                .id(jobId).userId(USER_ID).connectorCode("time")
                .connectionId(connectionId.toString())
                .kind(ConnectorJobKind.SYSTEM).name("sync").type(ConnectorJobType.PERIODIC)
                .status(ConnectorJobStatus.PENDING).timeoutSeconds(30).build();
        when(connectorJobRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(job)));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "list_connector_jobs",
                Map.of("connectorCode", "time", "kind", "SYSTEM",
                        "connectionId", connectionId.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<ConnectorJob>> captor =
                ArgumentCaptor.forClass(Specification.class);
        verify(connectorJobRepository).findAll(captor.capture(), any(Pageable.class));
        Specification<ConnectorJob> spec = captor.getValue();
        assertNotNull(spec);

        // Оцениваем спецификацию на замоканом criteria API: каждый предикат доходит до cb.equal
        // со своим значением — так проверяется, что фильтры действительно собраны.
        Root<ConnectorJob> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(cb.equal(any(), any())).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(mock(Predicate.class));
        spec.toPredicate(root, query, cb);

        verify(cb).equal(any(), eq(USER_ID));
        verify(cb).equal(any(), eq("time"));
        verify(cb).equal(any(), eq(ConnectorJobKind.SYSTEM));
        verify(cb).equal(any(), eq(connectionId.toString()));

        // SYSTEM-джоба присутствует в выдаче (листинг их не скрывает).
        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("SYSTEM", ((Map<?, ?>) items.getFirst()).get("kind"));
    }
}
