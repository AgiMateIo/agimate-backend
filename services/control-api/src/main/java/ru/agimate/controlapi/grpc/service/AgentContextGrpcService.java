package ru.agimate.controlapi.grpc.service;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.integrations.mcp.McpToolMapper;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.ConnectionRef;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.GetAgentSpecRequest;
import ru.agimate.agentworker.GetConnectionToolsRequest;
import ru.agimate.agentworker.GetConnectionToolsResponse;
import ru.agimate.agentworker.GetConnectionsRequest;
import ru.agimate.agentworker.GetConnectionsResponse;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetSkillRequest;
import ru.agimate.agentworker.GetSkillsRequest;
import ru.agimate.agentworker.GetSkillsResponse;
import ru.agimate.agentworker.GetTeamContextRequest;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.TeamContext;
import ru.agimate.agentworker.TeamMember;
import ru.agimate.agentworker.ToolAnnotations;
import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.GetMemoryRequest;
import ru.agimate.agentworker.GetMemoryNotesRequest;
import ru.agimate.agentworker.GetMemoryNotesResponse;
import ru.agimate.agentworker.MemoryNote;
import ru.agimate.controlapi.connectors.internal.persistentmemory.PersistentMemoryService;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.nullToEmpty;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.toJsonBytes;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AgentContextGrpcService extends AgentContextGrpc.AgentContextImplBase {

    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final SkillRepository skillRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentLlmRepository agentLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectorRegistry connectorRegistry;
    private final AgentSkillService agentSkillService;
    private final LlmProviderService llmProviderService;
    private final PersistentMemoryService persistentMemoryService;

    @Override
    public void getAgentSpec(GetAgentSpecRequest request, StreamObserver<AgentSpec> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
            if (!agent.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Agent is disabled").asRuntimeException());
                return;
            }

            String teamPubId = "";
            if (agent.getAgenticTeamId() != null) {
                teamPubId = agenticTeamRepository.findById(agent.getAgenticTeamId())
                        .map(t -> t.getId().toString())
                        .orElse("");
            }

            AgentSpec spec = AgentSpec.newBuilder()
                    .setAgentId(agent.getId().toString())
                    .setName(nullToEmpty(agent.getName()))
                    .setAgentType(agent.getType() == null ? "" : agent.getType().name())
                    .setSystemPrompt(nullToEmpty(agent.getInstructions()))
                    .setTeamId(teamPubId)
                    .build();

            log.debug("issued AgentSpec pool={} agent={}", WorkerPoolContextHolder.current().poolId(), agentId);
            responseObserver.onNext(spec);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getSkills(GetSkillsRequest request, StreamObserver<GetSkillsResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
            if (!agent.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Agent is disabled").asRuntimeException());
                return;
            }

            List<UUID> skillPubIds = agentSkillRepository.findByAgentId(agentId).stream()
                    .map(AgentSkill::getSkillId)
                    .toList();
            Map<UUID, AgentSkillWithConnectorsResponse> resolved =
                    agentSkillService.resolveSkillsById(skillPubIds);

            GetSkillsResponse.Builder builder = GetSkillsResponse.newBuilder();
            for (UUID skillPubId : skillPubIds) {
                AgentSkillWithConnectorsResponse skill = resolved.get(skillPubId);
                if (skill == null) {
                    continue;
                }
                SkillRef.Builder skillBuilder = SkillRef.newBuilder()
                        .setSkillId(skillPubId.toString())
                        .setName(nullToEmpty(skill.skillName()))
                        .setDescription(nullToEmpty(skill.description()))
                        .addAllConnectorCodes(skill.connectorCodes());
                builder.addSkills(skillBuilder.build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getSkill(GetSkillRequest request, StreamObserver<SkillSpec> responseObserver) {
        try {
            UUID skillId = parseUuid(request.getSkillId(), "skill_id");
            Skill skill = skillRepository.findByIdNotDeleted(skillId)
                    .orElseThrow(() -> new NotFoundStatusException("Skill not found: " + skillId));

            int requestedVersion;
            if (request.getVersion().isEmpty()) {
                requestedVersion = skill.getVersion();
            } else {
                try {
                    requestedVersion = Integer.parseInt(request.getVersion());
                } catch (NumberFormatException e) {
                    throw new BadRequestStatusException("Invalid skill version: " + request.getVersion());
                }
            }
            if (!skill.getVersion().equals(requestedVersion)) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Skill version mismatch: requested=" + requestedVersion
                                + ", current=" + skill.getVersion())
                        .asRuntimeException());
                return;
            }

            byte[] definitionJson = JsonUtils.writeValueAsString(Map.of(
                    "name", nullToEmpty(skill.getName()),
                    "description", nullToEmpty(skill.getDescription()),
                    "version", skill.getVersion()
            )).getBytes(StandardCharsets.UTF_8);

            String skillMd = nullToEmpty(skill.getMdContent());

            SkillSpec response = SkillSpec.newBuilder()
                    .setSkillId(skill.getId().toString())
                    .setVersion(Integer.toString(skill.getVersion()))
                    .setName(nullToEmpty(skill.getName()))
                    .setDescription(nullToEmpty(skill.getDescription()))
                    .setDefinitionJson(ByteString.copyFrom(definitionJson))
                    .setSkillMd(skillMd)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getTeamContext(GetTeamContextRequest request, StreamObserver<TeamContext> responseObserver) {
        try {
            UUID teamId = parseUuid(request.getTeamId(), "team_id");
            AgenticTeam team = agenticTeamRepository.findById(teamId)
                    .orElseThrow(() -> new NotFoundStatusException("Team not found: " + teamId));

            List<Agent> members = agentRepository
                    .findByUserIdAndAgenticTeamId(team.getUserId(), team.getId());

            TeamContext.Builder builder = TeamContext.newBuilder()
                    .setTeamId(team.getId().toString())
                    .setName(nullToEmpty(team.getName()))
                    .setDescription(nullToEmpty(team.getDescription()));
            for (Agent member : members) {
                builder.addMembers(TeamMember.newBuilder()
                        .setPubId(member.getId().toString())
                        .setName(nullToEmpty(member.getName()))
                        .setDescription(nullToEmpty(member.getDescription()))
                        .build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getLlmCredentials(GetLlmCredentialsRequest request, StreamObserver<LlmCredentials> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            AgentLlm llmBinding = agentLlmRepository.findAllByAgentIdOrderByName(agentId).stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No LLM binding for agent: " + agentId));
            LlmProvider provider = llmProviderRepository.findById(llmBinding.getLlmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + llmBinding.getLlmProviderId()));
            if (!provider.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("LLM provider disabled").asRuntimeException());
                return;
            }

            String apiKey = llmProviderService.decryptApiKey(provider);

            LlmCredentials response = LlmCredentials.newBuilder()
                    .setProviderType(provider.getProviderType().name())
                    .setBaseUrl(nullToEmpty(provider.getBaseUrl()))
                    .setApiKey(apiKey)
                    .setModel(nullToEmpty(llmBinding.getModel()))
                    .build();
            log.info("issued LLM credentials pool={} agent={} providerType={}",
                    WorkerPoolContextHolder.current().poolId(), agentId, provider.getProviderType());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getConnections(GetConnectionsRequest request, StreamObserver<GetConnectionsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
            if (!agent.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Agent is disabled").asRuntimeException());
                return;
            }

            // Привязанные (agent_connections) активные экземпляры — гейт доступности на уровне коннектора.
            GetConnectionsResponse.Builder builder = GetConnectionsResponse.newBuilder();
            for (Connection connection : connectionRepository.findActiveBoundToAgent(agentId)) {
                builder.addConnections(ConnectionRef.newBuilder()
                        .setId(connection.getId().toString())
                        .setConnectorCode(nullToEmpty(connection.getConnectorCode()))
                        .setNamespace(namespaceOf(connection))
                        .setName(nullToEmpty(connection.getName()))
                        .build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentContext.GetConnections failed pool={} agent={}", poolId, request.getAgentId(), e);
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getConnectionTools(GetConnectionToolsRequest request, StreamObserver<GetConnectionToolsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID connectionId = parseUuid(request.getConnectionId(), "connection_id");
            Connection connection = connectionRepository.findByIdNotDeleted(connectionId)
                    .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
            String connectorCode = connection.getConnectorCode();
            Connector connector = connectorRepository.findById(connectorCode)
                    .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

            // Контекст для листинга: достаточно connection_id (динамические коннекторы вроде mcp читают
            // тулы per-instance из кэша). Расшифровка credentials здесь не нужна.
            ConnectorContext listingContext = new ConnectorContext(connectionId.toString(), null, null, null, Map.of(), null);

            // Источник тулов по toolBinding: STATIC — рефлексия handler'а; DYNAMIC — connection_tools.
            Map<String, ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec> tools =
                    switch (connector.getToolBinding()) {
                        case STATIC -> connectorRegistry.findCapability(connectorCode, ToolProvider.class)
                                .orElseThrow(() -> new BadRequestStatusException("Unsupported connector: " + connectorCode))
                                .getTools(listingContext);
                        case DYNAMIC -> dynamicConnectionTools(connectionId);
                        case null -> throw new BadRequestStatusException(
                                "Connector does not expose tool definitions: " + connectorCode);
                    };

            String namespace = namespaceOf(connection);
            GetConnectionToolsResponse.Builder builder = GetConnectionToolsResponse.newBuilder();
            tools.forEach((name, spec) -> {
                ConnectorToolSpec.Builder toolBuilder = ConnectorToolSpec.newBuilder()
                        .setName(spec.name() != null ? spec.name() : name)
                        .setConnectionId(connectionId.toString())
                        .setNamespace(namespace);
                if (spec.title() != null) {
                    toolBuilder.setTitle(spec.title());
                }
                if (spec.description() != null) {
                    toolBuilder.setDescription(spec.description());
                }
                if (spec.inputSchema() != null) {
                    toolBuilder.setInputSchema(toJsonBytes(spec.inputSchema()));
                }
                if (spec.outputSchema() != null) {
                    toolBuilder.setOutputSchema(toJsonBytes(spec.outputSchema()));
                }
                var annotations = spec.annotations() != null
                        ? spec.annotations()
                        : ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec.DEFAULT;
                toolBuilder.setAnnotations(ToolAnnotations.newBuilder()
                        .setReadOnlyHint(annotations.readOnlyHint())
                        .setDestructiveHint(annotations.destructiveHint())
                        .setIdempotentHint(annotations.idempotentHint())
                        .setOpenWorldHint(annotations.openWorldHint())
                        .build());
                if (spec.meta() != null) {
                    toolBuilder.putAllMeta(spec.meta());
                }
                builder.addTools(toolBuilder.build());
            });
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentContext.GetConnectionTools failed pool={} connection={}",
                    poolId, request.getConnectionId(), e);
            handleError(e, responseObserver);
        }
    }

    /**
     * Неймспейс экземпляра для LLM-имени тула/триггера ({@code {namespace}.{name}}):
     * INSTANCE-коннекторы (mcp/telegram — у агента их может быть несколько) → {@code full_code}
     * (инстанс-уникальный handle); контекстные синглтоны (time/board/persist-memory — у агента ровно
     * один на тип) → {@code connector_code} (короткий и однозначный).
     */
    private static String namespaceOf(Connection connection) {
        String ns = connection.getIdentityScope() == IdentityScope.INSTANCE
                ? connection.getFullCode()
                : connection.getConnectorCode();
        return nullToEmpty(ns);
    }

    /** Тулы динамического экземпляра (mcp/app) из {@code connection_tools}. */
    private Map<String, ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec> dynamicConnectionTools(UUID connectionId) {
        Map<String, ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec> tools = new java.util.LinkedHashMap<>();
        connectionToolRepository.findActiveByConnectionId(connectionId)
                .forEach(tool -> tools.put(tool.getName(), McpToolMapper.toSpec(tool)));
        return tools;
    }

    @Override
    public void getMemory(GetMemoryRequest request, StreamObserver<AgentMemory> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID scopeId = persistentMemoryService.scopeIdForAgent(agentId).orElse(null);
            PersistentMemoryCold cold = scopeId == null ? null
                    : persistentMemoryService.getCold(scopeId).orElse(null);
            AgentMemory response = AgentMemory.newBuilder()
                    .setContent(cold == null ? "" : nullToEmpty(cold.getContent()))
                    .setVersion(cold == null ? 0 : cold.getVersion())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void getMemoryNotes(GetMemoryNotesRequest request, StreamObserver<GetMemoryNotesResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID scopeId = persistentMemoryService.scopeIdForAgent(agentId).orElse(null);
            GetMemoryNotesResponse.Builder builder = GetMemoryNotesResponse.newBuilder();
            for (PersistentMemoryHot note : (scopeId == null ? java.util.List.<PersistentMemoryHot>of()
                    : persistentMemoryService.getNotes(scopeId))) {
                MemoryNote.Builder noteBuilder = MemoryNote.newBuilder()
                        .setId(note.getId().toString())
                        .setContent(nullToEmpty(note.getContent()));
                if (note.getSessionId() != null) {
                    noteBuilder.setSessionId(note.getSessionId().toString());
                }
                builder.addNotes(noteBuilder.build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

}
