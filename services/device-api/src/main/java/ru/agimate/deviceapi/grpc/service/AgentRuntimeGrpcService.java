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
import ru.agimate.worker.v1.AgentRuntimeGrpc;
import ru.agimate.worker.v1.AgentSkillRef;
import ru.agimate.worker.v1.AgentSpec;
import ru.agimate.worker.v1.GetAgentSpecRequest;
import ru.agimate.worker.v1.GetKnowledgeSectionRequest;
import ru.agimate.worker.v1.GetLlmCredentialsRequest;
import ru.agimate.worker.v1.GetSkillRequest;
import ru.agimate.worker.v1.GetTeamContextRequest;
import ru.agimate.worker.v1.KnowledgeSection;
import ru.agimate.worker.v1.LlmCredentials;
import ru.agimate.worker.v1.SkillSpec;
import ru.agimate.worker.v1.TeamContext;

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

            List<AgentSkill> skillBindings = agentSkillRepository.findByAgentPubId(agentId);
            AgentSpec.Builder builder = AgentSpec.newBuilder()
                    .setAgentId(agent.getPubId().toString())
                    .setName(nullToEmpty(agent.getName()))
                    .setAgentType(agent.getType() == null ? "" : agent.getType().name())
                    .setSystemPrompt(nullToEmpty(agent.getPrompt()));
            for (AgentSkill bind : skillBindings) {
                builder.addSkills(AgentSkillRef.newBuilder()
                        .setSkillId(bind.getSkillPubId().toString())
                        .setVersion(bind.getInstalledSkillVersion() == null
                                ? "" : Integer.toString(bind.getInstalledSkillVersion()))
                        .build());
            }
            if (agent.getAgenticTeamId() != null) {
                agenticTeamRepository.findById(agent.getAgenticTeamId())
                        .ifPresent(team -> builder.setTeamId(team.getPubId().toString()));
            }
            agentLlmRepository.findAllByAgentPubIdOrderByName(agentId).stream().findFirst()
                    .ifPresent(llm -> builder.setLlmId(llm.getPubId().toString()));

            log.debug("issued AgentSpec pool={} agent={}", WorkerPoolContextHolder.current().poolId(), agentId);
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

            SkillSpec response = SkillSpec.newBuilder()
                    .setSkillId(skill.getPubId().toString())
                    .setVersion(Integer.toString(skill.getVersion()))
                    .setName(nullToEmpty(skill.getName()))
                    .setDescription(nullToEmpty(skill.getDescription()))
                    .setDefinitionJson(ByteString.copyFrom(definitionJson))
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

            List<UUID> memberIds = agentRepository
                    .findByUserPubIdAndAgenticTeamId(team.getUserPubId(), team.getId())
                    .stream().map(Agent::getPubId).toList();

            TeamContext.Builder builder = TeamContext.newBuilder()
                    .setTeamId(team.getPubId().toString())
                    .setName(nullToEmpty(team.getName()))
                    .setDescription(nullToEmpty(team.getDescription()));
            for (UUID memberId : memberIds) {
                builder.addMemberAgentIds(memberId.toString());
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

    @Override
    public void getKnowledgeSection(GetKnowledgeSectionRequest request, StreamObserver<KnowledgeSection> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("Knowledge Base is not implemented yet").asRuntimeException());
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
