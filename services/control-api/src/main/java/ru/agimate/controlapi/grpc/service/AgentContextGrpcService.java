package ru.agimate.controlapi.grpc.service;

import com.google.protobuf.ByteString;
import dev.langchain4j.agent.tool.ToolSpecification;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.controller.agent.dto.ToolSpecificationMapper;
import ru.agimate.controlapi.controller.agent.dto.ToolSpecificationResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorResponse;
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
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.SkillFileService;
import ru.agimate.controlapi.service.dto.AgentToolSpec;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.AgentToolDef;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.GetAgentSpecRequest;
import ru.agimate.agentworker.GetConnectorToolsRequest;
import ru.agimate.agentworker.GetConnectorToolsResponse;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetSkillRequest;
import ru.agimate.agentworker.GetSkillsRequest;
import ru.agimate.agentworker.GetSkillsResponse;
import ru.agimate.agentworker.GetTeamContextRequest;
import ru.agimate.agentworker.ListAgentToolsRequest;
import ru.agimate.agentworker.ListAgentToolsResponse;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.SkillConnectorRef;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.TeamContext;
import ru.agimate.agentworker.TeamMember;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AgentContextGrpcService extends AgentContextGrpc.AgentContextImplBase {

    private static final String API_KEY_FIELD = "api_key";

    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final SkillRepository skillRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentLlmRepository agentLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final ConnectorRepository connectorRepository;
    private final IntegrationEncryptionService encryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final AgentSkillService agentSkillService;
    private final AgentService agentService;
    private final SkillFileService skillFileService;

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
                    .setSystemPrompt(nullToEmpty(agent.getPrompt()))
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
                        .setDescription(nullToEmpty(skill.description()));
                for (SkillConnectorResponse connector : skill.connectors()) {
                    skillBuilder.addConnectors(SkillConnectorRef.newBuilder()
                            .setId(connector.id() == null ? "" : connector.id().toString())
                            .setConnectorCode(nullToEmpty(connector.connectorCode()))
                            .setType(connector.type() == null ? "" : connector.type().name())
                            .setName(nullToEmpty(connector.name()))
                            .build());
                }
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

            int requestedVersion = request.getVersion().isEmpty() ? skill.getVersion()
                    : Integer.parseInt(request.getVersion());
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

            String skillMd;
            try {
                skillMd = skillFileService.readSkillMd(skill.getId());
            } catch (NotFoundStatusException e) {
                skillMd = "";
            }

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

            String apiKey = encryptionService.decryptCredentials(provider.getEncryptedApiKey())
                    .get(API_KEY_FIELD);
            if (apiKey == null) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Provider has no api_key").asRuntimeException());
                return;
            }

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
    public void getConnectorTools(GetConnectorToolsRequest request, StreamObserver<GetConnectorToolsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            String connectorCode = request.getConnectorCode();
            if (connectorCode.isEmpty()) {
                throw Status.INVALID_ARGUMENT.withDescription("connector_code is required").asRuntimeException();
            }

            Connector connector = connectorRepository.findById(connectorCode)
                    .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

            Map<String, ToolSpecification> tools = switch (connector.getType()) {
                case INTEGRATION, INTERNAL_SERVICE -> connectorRegistry.findHandler(connectorCode)
                        .orElseThrow(() -> new BadRequestStatusException("Unsupported connector: " + connectorCode))
                        .getTools();
                case APP, LOOPBACK -> throw new BadRequestStatusException(
                        "Connector type " + connector.getType() + " does not expose static tool definitions");
            };

            GetConnectorToolsResponse.Builder builder = GetConnectorToolsResponse.newBuilder();
            tools.forEach((name, spec) -> {
                ToolSpecificationResponse dto = ToolSpecificationMapper.toResponse(spec);
                ConnectorToolSpec.Builder toolBuilder = ConnectorToolSpec.newBuilder()
                        .setName(dto.name() != null ? dto.name() : name);
                if (dto.description() != null) {
                    toolBuilder.setDescription(dto.description());
                }
                if (dto.parameters() != null) {
                    String json = JsonUtils.writeValueAsString(dto.parameters());
                    toolBuilder.setParametersJsonSchema(
                            ByteString.copyFrom(json.getBytes(StandardCharsets.UTF_8)));
                }
                builder.addTools(toolBuilder.build());
            });
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentContext.GetConnectorTools failed pool={} connector={}",
                    poolId, request.getConnectorCode(), e);
            handleError(e, responseObserver);
        }
    }

    @Override
    public void listAgentTools(ListAgentToolsRequest request, StreamObserver<ListAgentToolsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            ListAgentToolsResponse.Builder builder = ListAgentToolsResponse.newBuilder();
            for (AgentToolSpec spec : agentService.getAvailableToolSpecs(agentId)) {
                AgentToolDef.Builder toolBuilder = AgentToolDef.newBuilder()
                        .setName(spec.name());
                if (spec.description() != null) {
                    toolBuilder.setDescription(spec.description());
                }
                if (spec.parametersJsonSchema() != null) {
                    String json = JsonUtils.writeValueAsString(spec.parametersJsonSchema());
                    toolBuilder.setParametersJsonSchema(
                            ByteString.copyFrom(json.getBytes(StandardCharsets.UTF_8)));
                }
                builder.addTools(toolBuilder.build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentContext.ListAgentTools failed pool={} agent={}",
                    poolId, request.getAgentId(), e);
            handleError(e, responseObserver);
        }
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw Status.INVALID_ARGUMENT
                    .withDescription(field + " is required").asRuntimeException();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription(field + " is not a valid UUID").asRuntimeException();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void handleError(Exception e, StreamObserver<?> observer) {
        if (e instanceof io.grpc.StatusRuntimeException sre) {
            observer.onError(sre);
            return;
        }
        if (e instanceof NotFoundStatusException) {
            observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        if (e instanceof BadRequestStatusException || e instanceof ValidationErrorStatusException) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        log.error("AgentContext RPC failed", e);
        observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
    }
}
