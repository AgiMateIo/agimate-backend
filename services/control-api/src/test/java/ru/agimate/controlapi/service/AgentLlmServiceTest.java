package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.LlmSource;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;
import ru.agimate.controlapi.service.AgentLlmService.Binding;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLlmService — цепочка привязок и источник моделей")
class AgentLlmServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private final UUID userId = UUID.randomUUID();

    @Mock
    private AgentLlmRepository agentLlmRepository;
    @Mock
    private PlatformLlmRepository platformLlmRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LlmProviderRepository llmProviderRepository;
    @Mock
    private LlmProviderService llmProviderService;

    @InjectMocks
    private AgentLlmService service;

    private static LlmProvider platformProvider() {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name("platform")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://openrouter.ai/api/v1")
                .enabled(true)
                .build();
    }

    private LlmProvider userProvider() {
        LlmProvider provider = LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name("my-openai")
                .providerType(LlmProviderType.OPENAI)
                .enabled(true)
                .build();
        when(llmProviderService.requireOwned(provider.getId(), userId)).thenReturn(provider);
        return provider;
    }

    private void stubOwnedAgent(LlmSource source) {
        when(agentRepository.findById(AGENT_ID))
                .thenReturn(Optional.of(Agent.builder()
                        .id(AGENT_ID).userId(userId).type(AgentType.GENERIC).llmSource(source).build()));
    }

    private static AgentLlm binding(LlmProvider provider, String model, LlmPurpose purpose, int priority) {
        return AgentLlm.builder()
                .agentId(AGENT_ID)
                .userId(UUID.randomUUID())
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(purpose)
                .priority(priority)
                .build();
    }

    private static PlatformLlm platformBinding(LlmProvider provider, String model, LlmPurpose purpose,
                                               int priority) {
        return PlatformLlm.builder()
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(purpose)
                .priority(priority)
                .build();
    }

    @Nested
    @DisplayName("POST — привязка добавляется в конец цепочки")
    class Append {

        @Test
        @DisplayName("на пустом назначении получает priority 0")
        void firstBindingGetsZero() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(agentLlmRepository.save(any(AgentLlm.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentLlm binding = service.create(AGENT_ID, userId, provider.getId(), "gpt-4o", LlmPurpose.CHAT);

            assertEquals(0, binding.getPriority());
            assertEquals(LlmPurpose.CHAT, binding.getPurpose());
            verify(llmProviderService).validateModelsKnown(provider, List.of("gpt-4o"));
        }

        @Test
        @DisplayName("к существующей цепочке добавляется с priority max + 1")
        void appendsAfterMax() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of(
                            binding(provider, "first", LlmPurpose.CHAT, 0),
                            binding(provider, "second", LlmPurpose.CHAT, 1)));
            when(agentLlmRepository.save(any(AgentLlm.class))).thenAnswer(inv -> inv.getArgument(0));

            assertEquals(2, service.create(AGENT_ID, userId, provider.getId(), "third", LlmPurpose.CHAT)
                    .getPriority());
        }

        @Test
        @DisplayName("дубль (провайдер, модель) в назначении → 409, строка не пишется")
        void duplicatePairConflicts() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.IMAGE))
                    .thenReturn(List.of(binding(provider, "image-model", LlmPurpose.IMAGE, 0)));

            assertThrows(ConflictStatusException.class, () -> service.create(
                    AGENT_ID, userId, provider.getId(), "image-model", LlmPurpose.IMAGE));
            verify(agentLlmRepository, never()).save(any());
        }

        @Test
        @DisplayName("цепочка уже из пяти привязок → 409, шестая не пишется")
        void fullChainConflicts() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of(
                            binding(provider, "m0", LlmPurpose.CHAT, 0),
                            binding(provider, "m1", LlmPurpose.CHAT, 1),
                            binding(provider, "m2", LlmPurpose.CHAT, 2),
                            binding(provider, "m3", LlmPurpose.CHAT, 3),
                            binding(provider, "m4", LlmPurpose.CHAT, 4)));

            assertThrows(ConflictStatusException.class, () -> service.create(
                    AGENT_ID, userId, provider.getId(), "m5", LlmPurpose.CHAT));
            verify(agentLlmRepository, never()).save(any());
        }

        @Test
        @DisplayName("проигранная гонка за priority → 409, а не исключение персистентности")
        void lostRaceIsConflict() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(agentLlmRepository.save(any(AgentLlm.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new DataIntegrityViolationException("uq_agent_llms_agent_id_purpose_priority"))
                    .when(agentLlmRepository).flush();

            assertThrows(ConflictStatusException.class, () -> service.create(
                    AGENT_ID, userId, provider.getId(), "gpt-4o", LlmPurpose.CHAT));
        }

        @Test
        @DisplayName("purpose не задан → CHAT по умолчанию")
        void purposeDefaultsToChat() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(agentLlmRepository.save(any(AgentLlm.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentLlmResponse response = service.create(AGENT_ID, userId,
                    new CreateAgentLlmRequest(provider.getId(), "gpt-4o", null));

            assertEquals(LlmPurpose.CHAT, response.purpose());
            assertEquals(0, response.priority());
            assertTrue(response.active());
        }

        @Test
        @DisplayName("PLATFORM-агенту manage-добавление запрещено — 400, платёж меняется только переключателем")
        void managedAppendOnPlatformRejected() {
            stubOwnedAgent(LlmSource.PLATFORM);

            assertThrows(BadRequestStatusException.class, () -> service.create(
                    AGENT_ID, userId, UUID.randomUUID(), "gpt-4o", LlmPurpose.CHAT));
            verify(agentLlmRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("PUT — цепочка назначения заменяется списком")
    class ReplaceChain {

        @Test
        @DisplayName("старые строки назначения удаляются, новые получают priority по позиции")
        void replacesWithPositions() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            AgentLlm old = binding(provider, "old", LlmPurpose.IMAGE, 0);
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.IMAGE))
                    .thenReturn(List.of(old));
            when(agentLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<AgentLlm> chain = service.replaceChain(AGENT_ID, userId, LlmPurpose.IMAGE,
                    List.of(new Binding(provider.getId(), "first"), new Binding(provider.getId(), "second")));

            verify(agentLlmRepository).deleteAllInBatch(List.of(old));
            verify(llmProviderService).validateModelsKnown(provider, List.of("first"));
            verify(llmProviderService).validateModelsKnown(provider, List.of("second"));
            assertEquals(List.of("first", "second"),
                    chain.stream().map(AgentLlm::getModel).toList());
            assertEquals(List.of(0, 1), chain.stream().map(AgentLlm::getPriority).toList());
        }

        @Test
        @DisplayName("пустое назначение — PUT создаёт цепочку, а не отвечает 404")
        void createsOnEmptyPurpose() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.VISION))
                    .thenReturn(List.of());
            when(agentLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<AgentLlm> chain = service.replaceChain(AGENT_ID, userId, LlmPurpose.VISION,
                    List.of(new Binding(provider.getId(), "vision-model")));

            assertEquals(1, chain.size());
            assertEquals(0, chain.get(0).getPriority());
        }

        @Test
        @DisplayName("пустой список снимает назначение целиком")
        void emptyListClearsPurpose() {
            stubOwnedAgent(LlmSource.BYOK);
            UUID providerId = UUID.randomUUID();
            AgentLlm old = AgentLlm.builder()
                    .agentId(AGENT_ID).llmProviderId(providerId).model("old")
                    .purpose(LlmPurpose.IMAGE).build();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.IMAGE))
                    .thenReturn(List.of(old));
            when(agentLlmRepository.saveAll(anyList())).thenReturn(List.of());

            assertTrue(service.replaceChain(AGENT_ID, userId, LlmPurpose.IMAGE, List.of()).isEmpty());
            verify(agentLlmRepository).deleteAllInBatch(List.of(old));
        }

        @Test
        @DisplayName("дубль (провайдер, модель) внутри списка → 400, ничего не пишется")
        void duplicateInListRejected() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();

            assertThrows(BadRequestStatusException.class, () -> service.replaceChain(AGENT_ID, userId,
                    LlmPurpose.IMAGE, List.of(
                            new Binding(provider.getId(), "same"),
                            new Binding(provider.getId(), "same"))));
            verify(agentLlmRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("список длиннее пяти → 409, ничего не пишется")
        void tooLongRejected() {
            stubOwnedAgent(LlmSource.BYOK);
            UUID providerId = UUID.randomUUID();

            List<Binding> six = List.of(
                    new Binding(providerId, "m0"), new Binding(providerId, "m1"),
                    new Binding(providerId, "m2"), new Binding(providerId, "m3"),
                    new Binding(providerId, "m4"), new Binding(providerId, "m5"));

            assertThrows(ConflictStatusException.class,
                    () -> service.replaceChain(AGENT_ID, userId, LlmPurpose.IMAGE, six));
            verify(agentLlmRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("проигранная гонка PUT с другим писателем → 409, а не исключение персистентности")
        void lostReplaceRaceIsConflict() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.IMAGE))
                    .thenReturn(List.of());
            when(agentLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new DataIntegrityViolationException("uq_agent_llms_agent_id_purpose_priority"))
                    .when(agentLlmRepository).flush();

            assertThrows(ConflictStatusException.class, () -> service.replaceChain(AGENT_ID, userId,
                    LlmPurpose.IMAGE, List.of(new Binding(provider.getId(), "photo"))));
        }

        @Test
        @DisplayName("каждый элемент проходит requireOwned — чужого провайдера в цепочку не подложить")
        void everyElementIsOwned() {
            stubOwnedAgent(LlmSource.BYOK);
            LlmProvider mine = userProvider();
            UUID foreign = UUID.randomUUID();
            when(llmProviderService.requireOwned(foreign, userId))
                    .thenThrow(new NotFoundStatusException("LLM provider not found"));

            assertThrows(NotFoundStatusException.class, () -> service.replaceChain(AGENT_ID, userId,
                    LlmPurpose.IMAGE, List.of(
                            new Binding(mine.getId(), "ok"),
                            new Binding(foreign, "foreign"))));
            verify(agentLlmRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("PLATFORM-агенту непустая замена запрещена — 400")
        void nonEmptyReplaceOnPlatformRejected() {
            stubOwnedAgent(LlmSource.PLATFORM);

            assertThrows(BadRequestStatusException.class, () -> service.replaceChain(AGENT_ID, userId,
                    LlmPurpose.IMAGE, List.of(new Binding(UUID.randomUUID(), "photo"))));
            verify(agentLlmRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("PLATFORM-агенту PUT [] разрешён — это уборка, платёж не меняется")
        void emptyReplaceOnPlatformIsCleanup() {
            stubOwnedAgent(LlmSource.PLATFORM);
            UUID providerId = UUID.randomUUID();
            AgentLlm old = AgentLlm.builder()
                    .agentId(AGENT_ID).llmProviderId(providerId).model("old")
                    .purpose(LlmPurpose.IMAGE).build();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.IMAGE))
                    .thenReturn(List.of(old));
            when(agentLlmRepository.saveAll(anyList())).thenReturn(List.of());

            assertTrue(service.replaceChain(AGENT_ID, userId, LlmPurpose.IMAGE, List.of()).isEmpty());
            verify(agentLlmRepository).deleteAllInBatch(List.of(old));
        }
    }

    @Nested
    @DisplayName("DELETE — уборка назначения")
    class Delete {

        @Test
        @DisplayName("неактивная привязка PLATFORM-агента удаляется — это уборка, платёж не меняется")
        void inactiveBindingOnPlatformIsDeletable() {
            stubOwnedAgent(LlmSource.PLATFORM);
            LlmProvider provider = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("my-openai")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
            AgentLlm old = binding(provider, "stale", LlmPurpose.CHAT, 0);
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of(old));

            service.delete(AGENT_ID, userId, LlmPurpose.CHAT);

            verify(agentLlmRepository).deleteAllInBatch(List.of(old));
        }

        @Test
        @DisplayName("пустое назначение — 404")
        void missingChainIsNotFound() {
            stubOwnedAgent(LlmSource.BYOK);
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of());

            assertThrows(NotFoundStatusException.class,
                    () -> service.delete(AGENT_ID, userId, LlmPurpose.CHAT));
        }
    }

    @Nested
    @DisplayName("setAgentLlm — исключение коннектора")
    class SetAgentLlm {

        @Test
        @DisplayName("непустая цепочка на PLATFORM-агенте переключает его в BYOK")
        void nonEmptyChainSwitchesToByok() {
            Agent agent = Agent.builder()
                    .id(AGENT_ID).userId(userId).type(AgentType.GENERIC).llmSource(LlmSource.PLATFORM).build();
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
            LlmProvider provider = userProvider();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(agentLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            List<AgentLlm> chain = service.setAgentLlm(AGENT_ID, userId, LlmPurpose.CHAT,
                    List.of(new Binding(provider.getId(), "gpt-4o")));

            assertEquals(LlmSource.BYOK, agent.getLlmSource());
            verify(agentRepository).save(agent);
            assertEquals(1, chain.size());
        }

        @Test
        @DisplayName("пустая цепочка на PLATFORM-агенте — уборка без смены источника")
        void emptyChainDoesNotSwitch() {
            Agent agent = Agent.builder()
                    .id(AGENT_ID).userId(userId).type(AgentType.GENERIC).llmSource(LlmSource.PLATFORM).build();
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(agentLlmRepository.saveAll(anyList())).thenReturn(List.of());

            service.setAgentLlm(AGENT_ID, userId, LlmPurpose.CHAT, List.of());

            assertEquals(LlmSource.PLATFORM, agent.getLlmSource());
            verify(agentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("runtime-листинги")
    class Runtime {

        private void stubByokAgent() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                    Agent.builder().id(AGENT_ID).userId(userId).type(AgentType.GENERIC)
                            .llmSource(LlmSource.BYOK).build()));
        }

        @Test
        @DisplayName("GET по назначению отдаёт первую по priority привязку с включённым провайдером")
        void firstEnabledBinding() {
            stubByokAgent();
            LlmProvider enabled = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("on")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
            LlmProvider disabled = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("off")
                    .providerType(LlmProviderType.OPENAI).enabled(false).build();
            AgentLlm first = binding(disabled, "dead", LlmPurpose.CHAT, 0);
            AgentLlm second = binding(enabled, "alive", LlmPurpose.CHAT, 1);
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of(first, second));
            when(llmProviderRepository.findAllByIdIn(List.of(disabled.getId(), enabled.getId())))
                    .thenReturn(List.of(disabled, enabled));

            assertEquals("alive", service.runtimeForAgentByPurpose(AGENT_ID, LlmPurpose.CHAT).model());
        }

        @Test
        @DisplayName("назначение без строк → 404")
        void noBindingsNotFound() {
            stubByokAgent();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.VISION))
                    .thenReturn(List.of());

            assertThrows(NotFoundStatusException.class,
                    () -> service.runtimeForAgentByPurpose(AGENT_ID, LlmPurpose.VISION));
        }

        @Test
        @DisplayName("все провайдеры назначения выключены → 404, а не строка выключенного")
        void allDisabledNotFound() {
            stubByokAgent();
            LlmProvider disabled = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("off")
                    .providerType(LlmProviderType.OPENAI).enabled(false).build();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(AGENT_ID, LlmPurpose.CHAT))
                    .thenReturn(List.of(binding(disabled, "dead", LlmPurpose.CHAT, 0)));
            when(llmProviderRepository.findAllByIdIn(List.of(disabled.getId())))
                    .thenReturn(List.of(disabled));

            assertThrows(NotFoundStatusException.class,
                    () -> service.runtimeForAgentByPurpose(AGENT_ID, LlmPurpose.CHAT));
        }

        @Test
        @DisplayName("PLATFORM-агент наружу не отдаёт ни одной привязки: список пуст, назначение — 404")
        void platformSourceExposesNothing() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                    Agent.builder().id(AGENT_ID).userId(userId).type(AgentType.GENERIC)
                            .llmSource(LlmSource.PLATFORM).build()));

            assertTrue(service.runtimeForAgent(AGENT_ID).isEmpty());
            assertThrows(NotFoundStatusException.class,
                    () -> service.runtimeForAgentByPurpose(AGENT_ID, LlmPurpose.CHAT));
        }

        @Test
        @DisplayName("назначения идут в порядке объявления LlmPurpose, а не по алфавиту TEXT-колонки")
        void purposesFollowEnumOrder() {
            stubByokAgent();
            LlmProvider provider = LlmProvider.builder()
                    .id(UUID.randomUUID()).name("my-openai")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
            when(agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(AGENT_ID))
                    .thenReturn(List.of(binding(provider, "vision", LlmPurpose.VISION, 0),
                            binding(provider, "chat", LlmPurpose.CHAT, 0)));
            when(llmProviderRepository.findAllByIdIn(List.of(provider.getId())))
                    .thenReturn(List.of(provider));

            assertEquals(List.of(LlmPurpose.CHAT, LlmPurpose.VISION),
                    service.listForAgent(AGENT_ID, userId).stream()
                            .map(AgentLlmResponse::purpose).toList());
        }

        @Test
        @DisplayName("список для агента отдаёт priority каждой привязки")
        void listCarriesPriority() {
            stubByokAgent();
            LlmProvider provider = LlmProvider.builder()
                    .id(UUID.randomUUID()).name("my-openai")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
            when(agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(AGENT_ID))
                    .thenReturn(List.of(binding(provider, "m0", LlmPurpose.CHAT, 0),
                            binding(provider, "m1", LlmPurpose.CHAT, 1)));
            when(llmProviderRepository.findAllByIdIn(List.of(provider.getId())))
                    .thenReturn(List.of(provider));

            List<AgentLlmResponse> llms = service.listForAgent(AGENT_ID, userId);

            assertEquals(List.of(0, 1), llms.stream().map(AgentLlmResponse::priority).toList());
            assertTrue(llms.stream().allMatch(AgentLlmResponse::active));
        }

        @Test
        @DisplayName("PLATFORM-агент: платформенные строки эффективны, свои — неактивны")
        void platformListingShowsPlatformRowsAndInactiveOwnRows() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                    Agent.builder().id(AGENT_ID).userId(userId).type(AgentType.GENERIC)
                            .llmSource(LlmSource.PLATFORM).build()));
            LlmProvider platform = platformProvider();
            PlatformLlm platformChat = platformBinding(platform, "plat-chat", LlmPurpose.CHAT, 0);
            PlatformLlm platformImage = platformBinding(platform, "plat-image", LlmPurpose.IMAGE, 0);
            when(platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc())
                    .thenReturn(List.of(platformChat, platformImage));
            when(llmProviderRepository.findAllByIdIn(List.of(platform.getId())))
                    .thenReturn(List.of(platform));

            LlmProvider own = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("my-openai")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
            when(agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(AGENT_ID))
                    .thenReturn(List.of(binding(own, "own-chat", LlmPurpose.CHAT, 0)));
            when(llmProviderRepository.findAllByIdIn(List.of(own.getId())))
                    .thenReturn(List.of(own));

            List<AgentLlmResponse> llms = service.listForAgent(AGENT_ID, userId);

            assertEquals(3, llms.size());
            AgentLlmResponse chat = llms.get(0);
            assertEquals(AgentLlmResponse.Source.PLATFORM, chat.source());
            assertEquals(0, chat.priority());
            assertNull(chat.llmProviderId(), "платформенный провайдер не адресуем пользователем");
            assertTrue(chat.active());

            AgentLlmResponse ownChat = llms.get(1);
            assertEquals(AgentLlmResponse.Source.USER, ownChat.source());
            assertFalse(ownChat.active(), "свои строки PLATFORM-агента неактивны");

            assertEquals(LlmPurpose.IMAGE, llms.get(2).purpose());
        }

        @Test
        @DisplayName("внешнему PLATFORM-агенту видны только медиа-назначения платформы")
        void externalPlatformListingOnlyMedia() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                    Agent.builder().id(AGENT_ID).userId(userId).type(AgentType.MCP)
                            .llmSource(LlmSource.PLATFORM).build()));
            LlmProvider platform = platformProvider();
            when(platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc()).thenReturn(List.of(
                    platformBinding(platform, "plat-chat", LlmPurpose.CHAT, 0),
                    platformBinding(platform, "plat-routine", LlmPurpose.ROUTINE, 0),
                    platformBinding(platform, "plat-image", LlmPurpose.IMAGE, 0)));
            when(llmProviderRepository.findAllByIdIn(List.of(platform.getId())))
                    .thenReturn(List.of(platform));
            when(agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(AGENT_ID))
                    .thenReturn(List.of());

            assertEquals(List.of(LlmPurpose.IMAGE),
                    service.listForAgent(AGENT_ID, userId).stream()
                            .map(AgentLlmResponse::purpose).toList());
        }
    }

    @Nested
    @DisplayName("listForAgents")
    class ListForAgents {

        @Test
        @DisplayName("PLATFORM-агент без привязок показывает платформенную строку с фактическим priority")
        void platformEntryForAgentWithoutBindings() {
            LlmProvider platform = platformProvider();
            when(agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAscPriorityAsc(List.of(AGENT_ID)))
                    .thenReturn(List.of());
            when(platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc())
                    .thenReturn(List.of(platformBinding(platform, "gpt-5-mini", LlmPurpose.CHAT, 0)));
            when(llmProviderRepository.findAllByIdIn(List.of(platform.getId())))
                    .thenReturn(List.of(platform));
            Agent agent = Agent.builder()
                    .id(AGENT_ID).userId(userId).type(AgentType.GENERIC)
                    .llmSource(LlmSource.PLATFORM).build();

            Map<UUID, List<AgentLlmResponse>> result = service.listForAgents(List.of(agent));

            List<AgentLlmResponse> llms = result.get(AGENT_ID);
            assertEquals(1, llms.size());
            AgentLlmResponse entry = llms.get(0);
            assertEquals(AgentLlmResponse.Source.PLATFORM, entry.source());
            assertEquals("gpt-5-mini", entry.model());
            assertEquals(0, entry.priority());
            assertTrue(entry.active());
            assertNull(entry.llmProviderId(), "платформенный провайдер не адресуем пользователем");
        }

        @Test
        @DisplayName("своя привязка PLATFORM-агента попадает в ответ неактивной")
        void userBindingsStayInactiveOnPlatform() {
            UUID providerId = UUID.randomUUID();
            AgentLlm binding = AgentLlm.builder()
                    .agentId(AGENT_ID)
                    .llmProviderId(providerId)
                    .model("user-model")
                    .build();
            LlmProvider provider = LlmProvider.builder()
                    .id(providerId)
                    .name("my-openai")
                    .providerType(LlmProviderType.OPENAI)
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAscPriorityAsc(List.of(AGENT_ID)))
                    .thenReturn(List.of(binding));
            when(llmProviderRepository.findAllByIdIn(List.of(providerId)))
                    .thenReturn(List.of(provider));
            when(platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc()).thenReturn(List.of());
            Agent agent = Agent.builder()
                    .id(AGENT_ID).userId(userId).type(AgentType.GENERIC)
                    .llmSource(LlmSource.PLATFORM).build();

            Map<UUID, List<AgentLlmResponse>> result = service.listForAgents(List.of(agent));

            List<AgentLlmResponse> llms = result.get(AGENT_ID);
            assertEquals(1, llms.size());
            assertEquals(AgentLlmResponse.Source.USER, llms.get(0).source());
            assertEquals(providerId, llms.get(0).llmProviderId());
            assertFalse(llms.get(0).active());
        }

        @Test
        @DisplayName("нет платформенных строк и привязок → пустой список у агента")
        void emptyWhenNoPlatformAndNoBindings() {
            when(agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAscPriorityAsc(List.of(AGENT_ID)))
                    .thenReturn(List.of());
            when(platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc()).thenReturn(List.of());
            Agent agent = Agent.builder()
                    .id(AGENT_ID).userId(userId).type(AgentType.GENERIC)
                    .llmSource(LlmSource.PLATFORM).build();

            Map<UUID, List<AgentLlmResponse>> result = service.listForAgents(List.of(agent));

            assertTrue(result.get(AGENT_ID).isEmpty());
        }
    }
}
