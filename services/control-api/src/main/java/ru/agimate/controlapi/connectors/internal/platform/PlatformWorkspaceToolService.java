package ru.agimate.controlapi.connectors.internal.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.AgentBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.OperationResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.BoardBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.BoardList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.ConnectorJobItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.ConnectorJobList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.PresetBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.PresetList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.TeamBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.TeamDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformWorkspaceDtos.TeamList;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;
import ru.agimate.controlapi.database.repositories.ConnectorJobSpecs;
import ru.agimate.controlapi.service.AgenticTeamService;
import ru.agimate.controlapi.service.ConnectorJobManageService;
import ru.agimate.controlapi.service.board.BoardService;
import ru.agimate.controlapi.service.dto.board.BoardCreateCommand;

import java.util.List;
import java.util.UUID;

/**
 * Tools of the platform connector's workspace module — the meta-agent manages agentic teams,
 * reads the preset gallery, configures task boards and controls connector background jobs on behalf of
 * its human owner ({@code env.userId}). A thin adapter: reads come from the repositories, writes go
 * through the existing services (command records / primitive-args overloads, so as not to drag in
 * {@code controller/**}). Domain {@link BaseHttpStatusException}s are translated into
 * {@link ConnectorException} so the message reaches the agent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformWorkspaceToolService {

    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentPresetRepository agentPresetRepository;
    private final BoardRepository boardRepository;
    private final ConnectorJobRepository connectorJobRepository;
    private final AgentRepository agentRepository;
    private final AgenticTeamService agenticTeamService;
    private final BoardService boardService;
    private final ConnectorJobManageService connectorJobManageService;

    // ---- teams -----------------------------------------------------------------------------

    @Tool(name = "list_teams", description = "List your agentic teams — the rosters of agents that "
            + "work together on a shared board",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public TeamList listTeams() {
        var capped = PlatformToolsSupport.cap(agenticTeamRepository
                .findByUserId(PlatformToolsSupport.userId()).stream()
                .limit(PlatformToolsSupport.MAX_LISTING + 1)
                .map(team -> new TeamBrief(team.getId().toString(), team.getName(), team.getDescription()))
                .toList());
        return new TeamList(capped.items(), capped.truncated());
    }

    @Tool(name = "get_team", description = "Get an agentic team with its roster — every agent of "
            + "yours placed into the team",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public TeamDetail getTeam(@ToolParam("Team public ID") String teamId) {
        AgenticTeam team = findOwnedTeam(teamId);
        List<AgentBrief> members = agentRepository
                .findByUserIdAndAgenticTeamId(team.getUserId(), team.getId()).stream()
                .map(PlatformWorkspaceToolService::toAgentBrief)
                .toList();
        return new TeamDetail(team.getId().toString(), team.getName(), team.getDescription(), members);
    }

    @Tool(name = "create_team",
            description = "Create an agentic team — a roster of agents that work together on a shared "
                    + "board. The name is unique per user",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public TeamDetail createTeam(
            @ToolParam("Team name (unique per user)") String name,
            @ToolParam(value = "Team description", required = false) String description) {
        AgenticTeam team = PlatformToolsSupport.domain(() -> agenticTeamService.create(
                PlatformToolsSupport.userId(),
                PlatformToolsSupport.requireText(name, "name"),
                PlatformToolsSupport.blankToNull(description)));
        return getTeam(team.getId().toString());
    }

    @Tool(name = "update_team",
            description = "Update an agentic team: rename it and/or replace its description. Omitted "
                    + "params are left unchanged; an empty string clears the description, a blank "
                    + "name is rejected",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public TeamDetail updateTeam(
            @ToolParam("Team public ID") String teamId,
            @ToolParam(value = "New name (empty string is rejected)", required = false) String name,
            @ToolParam(value = "New description; empty string clears", required = false) String description) {
        UUID id = PlatformToolsSupport.parseUuid(teamId, "teamId");
        // Raw strings through to the service overload: null = keep, blank name rejected, blank
        // description cleared — the service implements the PATCH convention itself.
        PlatformToolsSupport.domain(() -> agenticTeamService.patch(id, PlatformToolsSupport.userId(),
                name, description));
        return getTeam(teamId);
    }

    @Tool(name = "delete_team",
            description = "Delete an agentic team. Refused while the team has a board or agents assigned",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteTeam(@ToolParam("Team public ID") String teamId) {
        UUID id = PlatformToolsSupport.parseUuid(teamId, "teamId");
        PlatformToolsSupport.domain(() -> {
            agenticTeamService.delete(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Team deleted");
    }

    // ---- presets ---------------------------------------------------------------------------

    @Tool(name = "list_presets",
            description = "The gallery of agent role presets for the creation wizard: name, title, "
                    + "description, the skills it bundles and the agent type it proposes. Presets are "
                    + "admin-managed and offered read-only here",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public PresetList listPresets() {
        List<PresetBrief> items = agentPresetRepository.findAllByEnabledTrueOrderBySortOrderAscNameAsc()
                .stream()
                .map(PlatformWorkspaceToolService::toPresetBrief)
                .toList();
        return new PresetList(items);
    }

    // ---- boards ----------------------------------------------------------------------------

    @Tool(name = "list_boards", description = "List your task boards with the agentic team each belongs to",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public BoardList listBoards() {
        var capped = PlatformToolsSupport.cap(boardRepository.findByUserId(PlatformToolsSupport.userId()).stream()
                .limit(PlatformToolsSupport.MAX_LISTING + 1)
                .map(PlatformWorkspaceToolService::toBoardBrief)
                .toList());
        return new BoardList(capped.items(), capped.truncated());
    }

    @Tool(name = "create_board",
            description = "Create a task board for one of your agentic teams (one board per team)",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public BoardBrief createBoard(
            @ToolParam("Agentic team public ID the board belongs to") String teamId,
            @ToolParam("Board name") String name,
            @ToolParam(value = "Board description", required = false) String description) {
        UUID teamUuid = PlatformToolsSupport.parseUuid(teamId, "teamId");
        PlatformToolsSupport.domain(() -> boardService.create(PlatformToolsSupport.userId(),
                new BoardCreateCommand(teamUuid, PlatformToolsSupport.requireText(name, "name"),
                        PlatformToolsSupport.blankToNull(description))));
        // The board is unique per team — re-read it by its team to return the configured board.
        AgenticTeam team = agenticTeamRepository.findById(teamUuid)
                .orElseThrow(() -> new ConnectorException("Board not found"));
        Board board = boardRepository.findByAgenticTeam(team)
                .orElseThrow(() -> new ConnectorException("Board not found"));
        return toBoardBrief(board);
    }

    // ---- connector jobs --------------------------------------------------------------------

    @Tool(name = "list_connector_jobs",
            description = "Your connector background jobs — scheduled integrations and agent "
                    + "schedules. SYSTEM (declarative) jobs are included; they cannot be deleted, "
                    + "pause them instead. Optional filters: connector code, kind (SYSTEM, USER, "
                    + "AGENT) and connection",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectorJobList listConnectorJobs(
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode,
            @ToolParam(value = "Filter by kind: SYSTEM, USER or AGENT", required = false) String kind,
            @ToolParam(value = "Filter by connection public ID (jobs of one instance)", required = false)
            String connectionId) {
        Specification<ConnectorJob> spec = ConnectorJobSpecs.ownedBy(PlatformToolsSupport.userId());
        String code = PlatformToolsSupport.blankToNull(connectorCode);
        if (code != null) {
            spec = spec.and(ConnectorJobSpecs.hasConnector(code));
        }
        String kindValue = PlatformToolsSupport.blankToNull(kind);
        if (kindValue != null) {
            spec = spec.and(ConnectorJobSpecs.hasKind(
                    PlatformToolsSupport.parseEnum(ConnectorJobKind.class, kindValue, "kind")));
        }
        String connection = PlatformToolsSupport.blankToNull(connectionId);
        if (connection != null) {
            spec = spec.and(ConnectorJobSpecs.hasConnection(
                    PlatformToolsSupport.parseUuid(connection, "connectionId").toString()));
        }
        var page = connectorJobRepository.findAll(spec, PageRequest.of(0,
                PlatformToolsSupport.MAX_LISTING, Sort.by("nextRunAt").ascending()));
        List<ConnectorJobItem> items = page.map(PlatformWorkspaceToolService::toConnectorJobItem)
                .getContent();
        return new ConnectorJobList(items, PlatformToolsSupport.truncated(page));
    }

    @Tool(name = "pause_job", description = "Pause a connector job: the scheduler stops picking it "
            + "up. Idempotent — an already paused job stays paused",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult pauseJob(@ToolParam("Connector job public ID") String jobId) {
        UUID id = PlatformToolsSupport.parseUuid(jobId, "jobId");
        PlatformToolsSupport.domain(() -> {
            connectorJobManageService.pause(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Job paused");
    }

    @Tool(name = "resume_job", description = "Resume a paused connector job. Idempotent — a job "
            + "that is not paused stays as it is",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult resumeJob(@ToolParam("Connector job public ID") String jobId) {
        UUID id = PlatformToolsSupport.parseUuid(jobId, "jobId");
        PlatformToolsSupport.domain(() -> {
            connectorJobManageService.resume(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Job resumed");
    }

    @Tool(name = "run_job_now",
            description = "Schedule a connector job to run at once (the scheduler picks it up on its "
                    + "next tick). Refused while the job is paused or already running",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult runJobNow(@ToolParam("Connector job public ID") String jobId) {
        UUID id = PlatformToolsSupport.parseUuid(jobId, "jobId");
        PlatformToolsSupport.domain(() -> {
            connectorJobManageService.runNow(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Job scheduled to run now");
    }

    @Tool(name = "delete_job",
            description = "Delete a connector job you own. Declarative (SYSTEM) jobs cannot be "
                    + "deleted — pause them instead",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteJob(@ToolParam("Connector job public ID") String jobId) {
        UUID id = PlatformToolsSupport.parseUuid(jobId, "jobId");
        PlatformToolsSupport.domain(() -> {
            connectorJobManageService.delete(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Job deleted");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private AgenticTeam findOwnedTeam(String teamId) {
        UUID id = PlatformToolsSupport.parseUuid(teamId, "teamId");
        return agenticTeamRepository.findById(id)
                .filter(team -> team.getUserId().equals(PlatformToolsSupport.userId()))
                .orElseThrow(() -> new ConnectorException("Team not found"));
    }

    private static AgentBrief toAgentBrief(Agent agent) {
        return new AgentBrief(agent.getId().toString(), agent.getName(), agent.getDescription(),
                agent.getType().name(), agent.isEnabled(),
                agent.getAgenticTeamId() == null ? null : agent.getAgenticTeamId().toString());
    }

    private static PresetBrief toPresetBrief(AgentPreset preset) {
        return new PresetBrief(preset.getId().toString(), preset.getName(), preset.getTitle(),
                preset.getDescription(), preset.getSkillNames(),
                preset.getAgentType() == null ? null : preset.getAgentType().name(),
                preset.getSortOrder(), preset.isEnabled());
    }

    private static BoardBrief toBoardBrief(Board board) {
        return new BoardBrief(board.getId().toString(), board.getName(), board.getDescription(),
                board.getAgenticTeam().getId().toString());
    }

    private static ConnectorJobItem toConnectorJobItem(ConnectorJob job) {
        return new ConnectorJobItem(job.getId().toString(), job.getKind().name(),
                job.getConnectorCode(), job.getConnectionId(), job.getName(), job.getType().name(),
                job.getStatus().name(),
                job.getNextRunAt() != null ? job.getNextRunAt().toString() : null,
                job.getPausedAt() != null ? job.getPausedAt().toString() : null,
                job.getLastError());
    }
}
