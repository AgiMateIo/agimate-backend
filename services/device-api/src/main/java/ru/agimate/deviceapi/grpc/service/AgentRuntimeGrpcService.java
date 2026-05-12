package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.deviceapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.deviceapi.controller.manage.dto.SkillConnectorResponse;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentLlm;
import ru.agimate.deviceapi.database.entities.AgentSkill;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.database.repositories.AgentLlmRepository;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentSkillRepository;
import ru.agimate.deviceapi.database.repositories.AgenticTeamRepository;
import ru.agimate.deviceapi.database.repositories.LlmProviderRepository;
import ru.agimate.deviceapi.database.repositories.SkillRepository;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.deviceapi.service.AgentSkillService;
import ru.agimate.deviceapi.service.SkillFileService;
import ru.agimate.worker.v1.AgentRuntimeGrpc;
import ru.agimate.worker.v1.AgentSpec;
import ru.agimate.worker.v1.GetAgentSpecRequest;
import ru.agimate.worker.v1.GetLlmCredentialsRequest;
import ru.agimate.worker.v1.GetSkillRequest;
import ru.agimate.worker.v1.GetSkillsRequest;
import ru.agimate.worker.v1.GetSkillsResponse;
import ru.agimate.worker.v1.GetTeamContextRequest;
import ru.agimate.worker.v1.LlmCredentials;
import ru.agimate.worker.v1.SkillConnectorRef;
import ru.agimate.worker.v1.SkillRef;
import ru.agimate.worker.v1.SkillSpec;
import ru.agimate.worker.v1.TeamContext;
import ru.agimate.worker.v1.TeamMember;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AgentRuntimeGrpcService extends AgentRuntimeGrpc.AgentRuntimeImplBase {

    private static final String API_KEY_FIELD = "api_key";

    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final SkillRepository skillRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentLlmRepository agentLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final IntegrationEncryptionService encryptionService;
    private final AgentSkillService agentSkillService;
    private final SkillFileService skillFileService;

    @Override
    public void getAgentSpec(GetAgentSpecRequest request, StreamObserver<AgentSpec> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            Agent agent = agentRepository.findByPubId(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
            if (!agent.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Agent is disabled").asRuntimeException());
                return;
            }

            String teamPubId = "";
            if (agent.getAgenticTeamId() != null) {
                teamPubId = agenticTeamRepository.findById(agent.getAgenticTeamId())
                        .map(t -> t.getPubId().toString())
                        .orElse("");
            }

            AgentSpec spec = AgentSpec.newBuilder()
                    .setAgentId(agent.getPubId().toString())
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
            Agent agent = agentRepository.findByPubId(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
            if (!agent.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Agent is disabled").asRuntimeException());
                return;
            }

            List<UUID> skillPubIds = agentSkillRepository.findByAgentPubId(agentId).stream()
                    .map(AgentSkill::getSkillPubId)
                    .toList();
            Map<UUID, AgentSkillWithConnectorsResponse> resolved =
                    agentSkillService.resolveSkillsByPubId(skillPubIds);

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
            Skill skill = skillRepository.findByPubIdNotDeleted(skillId)
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
                skillMd = skillFileService.readSkillMd(skill.getPubId());
            } catch (NotFoundStatusException e) {
                skillMd = "";
            }

            SkillSpec response = SkillSpec.newBuilder()
                    .setSkillId(skill.getPubId().toString())
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
            AgenticTeam team = agenticTeamRepository.findByPubId(teamId)
                    .orElseThrow(() -> new NotFoundStatusException("Team not found: " + teamId));

            List<Agent> members = agentRepository
                    .findByUserPubIdAndAgenticTeamId(team.getUserPubId(), team.getId());

            TeamContext.Builder builder = TeamContext.newBuilder()
                    .setTeamId(team.getPubId().toString())
                    .setName(nullToEmpty(team.getName()))
                    .setDescription(nullToEmpty(team.getDescription()));
            for (Agent member : members) {
                builder.addMembers(TeamMember.newBuilder()
                        .setPubId(member.getPubId().toString())
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
            AgentLlm llmBinding = agentLlmRepository.findAllByAgentPubIdOrderByName(agentId).stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No LLM binding for agent: " + agentId));
            LlmProvider provider = llmProviderRepository.findByPubId(llmBinding.getLlmProviderPubId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + llmBinding.getLlmProviderPubId()));
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
        log.error("AgentRuntime RPC failed", e);
        observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
    }
}
