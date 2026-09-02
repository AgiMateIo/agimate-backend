package ru.agimate.controlapi.connectors.internal.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderCatalogRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.service.AgentLlmService;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.dto.llm.LlmProviderUpdateCommand;
import ru.agimate.controlapi.service.dto.llm.LlmUsageSnapshot;
import ru.agimate.controlapi.service.llm.LlmQuotaService;
import ru.agimate.controlapi.service.llm.LlmUsageQueryService;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PlatformLlmToolService")
class PlatformLlmToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SELF_AGENT_ID = UUID.randomUUID();
    private static final UUID OTHER_AGENT_ID = UUID.randomUUID();

    private final LlmProviderRepository llmProviderRepository = mock(LlmProviderRepository.class);
    private final LlmProviderModelRepository llmProviderModelRepository = mock(LlmProviderModelRepository.class);
    private final LlmProviderCatalogRepository llmProviderCatalogRepository = mock(LlmProviderCatalogRepository.class);
    private final AgentLlmRepository agentLlmRepository = mock(AgentLlmRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final LlmProviderService llmProviderService = mock(LlmProviderService.class);
    private final LlmQuotaService llmQuotaService = mock(LlmQuotaService.class);
    private final AgentLlmService agentLlmService = mock(AgentLlmService.class);
    private final LlmUsageQueryService llmUsageQueryService = mock(LlmUsageQueryService.class);

    private final PlatformLlmToolService llmTools = new PlatformLlmToolService(
            llmProviderRepository, llmProviderModelRepository, llmProviderCatalogRepository,
            agentLlmRepository, agentRepository, llmProviderService, llmQuotaService,
            agentLlmService, llmUsageQueryService);
    private final PlatformConnectorService handler = new PlatformConnectorService(
            mock(PlatformAgentToolService.class), mock(PlatformConnectionToolService.class), llmTools,
            mock(PlatformWorkspaceToolService.class), mock(PlatformObservabilityToolService.class));

    /** Инициатор вызова = SELF_AGENT_ID: операции над ним должны блокироваться. */
    private static ConnectorEnv selfEnv() {
        return new ConnectorEnv(null, USER_ID, SELF_AGENT_ID, null, null, null, Map.of(), null);
    }

    /** Инициатор вызова — другой агент того же владельца. */
    private static ConnectorEnv ownerEnv() {
        return new ConnectorEnv(null, USER_ID, OTHER_AGENT_ID, null, null, null, Map.of(), null);
    }

    private static LlmProvider provider(UUID userId) {
        return provider(userId, UUID.randomUUID());
    }

    private static LlmProvider provider(UUID userId, UUID id) {
        return LlmProvider.builder()
                .id(id)
                .userId(userId)
                .name("my-openai")
                .providerType(LlmProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .apiKeyMask("sk-AbCd...WxYz")
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("create_llm_provider: ничего не создаёт — возвращает setup-ссылку, ключ не параметр")
    void createReturnsSetupLinkAndWritesNothing() {
        ReflectionTestUtils.setField(llmTools, "frontendBaseUrl", "https://app.test");

        Map<?, ?> result = (Map<?, ?>)
                handler.executeTool(ownerEnv(), "create_llm_provider", Map.of(
                        "name", "my-openai",
                        "providerType", "OPENAI",
                        "baseUrl", "https://api.openai.com/v1"));

        assertEquals("setup_required", result.get("status"));
        String url = (String) result.get("setupUrl");
        assertTrue(url.startsWith("https://app.test/llm-providers/new?providerType=OPENAI&name=my-openai"),
                url);
        assertTrue(url.contains("baseUrl="), url);
        assertFalse(url.matches("(?i).*[?&](api)?key=.*"), "ссылка никогда не несёт ключ: " + url);
        verifyNoInteractions(llmProviderService);
    }

    @Test
    @DisplayName("create_llm_provider: OPENAI_COMPATIBLE без baseUrl — ошибка до возврата ссылки")
    void createCompatibleRequiresBaseUrl() {
        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(ownerEnv(),
                "create_llm_provider", Map.of("name", "my-gateway", "providerType", "OPENAI_COMPATIBLE")));
        assertTrue(ex.getMessage().contains("baseUrl"), ex.getMessage());
        verifyNoInteractions(llmProviderService);
    }

    @Test
    @DisplayName("get_llm_provider: только маска, ключа в результате нет")
    void getReturnsApiKeyMaskOnly() {
        LlmProvider provider = provider(USER_ID);
        when(llmProviderService.requireOwned(provider.getId(), USER_ID)).thenReturn(provider);

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "get_llm_provider",
                Map.of("id", provider.getId().toString()));

        assertFalse(result.containsKey("apiKey"), "get никогда не возвращает ключ");
        assertEquals("sk-AbCd...WxYz", result.get("apiKeyMask"));
        assertEquals("my-openai", result.get("name"));
    }

    @Test
    @DisplayName("update_llm_provider: apiKey не параметр тула — в команде всегда null, ротация через UI")
    void updateNeverCarriesAnApiKey() {
        LlmProvider provider = provider(USER_ID);
        when(llmProviderService.requireOwned(provider.getId(), USER_ID)).thenReturn(provider);
        when(llmProviderService.update(eq(provider.getId()), eq(USER_ID),
                any(LlmProviderUpdateCommand.class))).thenReturn(provider);

        handler.executeTool(ownerEnv(), "update_llm_provider",
                Map.of("id", provider.getId().toString(), "name", "renamed"));

        ArgumentCaptor<LlmProviderUpdateCommand> captor = ArgumentCaptor.forClass(LlmProviderUpdateCommand.class);
        verify(llmProviderService).update(eq(provider.getId()), eq(USER_ID), captor.capture());
        LlmProviderUpdateCommand cmd = captor.getValue();
        assertNull(cmd.apiKey(), "ключ всегда null — параметра нет, сервис сохраняет текущий ключ");
        assertEquals("renamed", cmd.name());
        assertNull(cmd.enabled());
    }

    @Test
    @DisplayName("create_llm_quota: 409 провайдера превращается в ConnectorException")
    void quotaCreateConflictIsTranslated() {
        UUID providerId = UUID.randomUUID();
        when(llmProviderService.requireOwned(providerId, USER_ID)).thenReturn(provider(USER_ID, providerId));
        when(llmQuotaService.create(eq(providerId), eq(UsageSubjectKind.USER), eq(UsageWindow.DAY), eq(1000L)))
                .thenThrow(new ConflictStatusException("Quota for this subject and window already exists"));

        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(ownerEnv(),
                "create_llm_quota", Map.of("providerId", providerId.toString(),
                        "subjectKind", "USER", "window", "DAY", "limitTokens", 1000L)));

        assertTrue(ex.getMessage().contains("already exists"), ex.getMessage());
    }

    @Test
    @DisplayName("create_llm_quota: мусор в subjectKind — ConnectorException с допустимыми значениями")
    void quotaCreateRejectsGarbageSubject() {
        UUID providerId = UUID.randomUUID();
        when(llmProviderService.requireOwned(providerId, USER_ID)).thenReturn(provider(USER_ID, providerId));

        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(ownerEnv(),
                "create_llm_quota", Map.of("providerId", providerId.toString(),
                        "subjectKind", "BOGUS", "window", "DAY", "limitTokens", 1000L)));

        assertTrue(ex.getMessage().contains("USER"), ex.getMessage());
        assertTrue(ex.getMessage().contains("AGENT"), ex.getMessage());
        verifyNoInteractions(llmQuotaService);
    }

    @Test
    @DisplayName("set_agent_llm: без существующей привязки — create с purpose CHAT по умолчанию")
    void agentLlmSetCreatesWhenAbsent() {
        UUID agentId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        stubOwnedAgent(agentId);
        AgentLlm binding = AgentLlm.builder()
                .id(UUID.randomUUID()).agentId(agentId).llmProviderId(providerId)
                .model("gpt-4o").purpose(LlmPurpose.CHAT).build();
        when(agentLlmRepository.findAllByAgentIdOrderByPurpose(agentId)).thenReturn(List.of());
        when(agentLlmService.create(eq(agentId), eq(USER_ID), eq(providerId), eq("gpt-4o"),
                eq(LlmPurpose.CHAT))).thenReturn(binding);
        when(llmProviderRepository.findById(providerId)).thenReturn(Optional.of(
                LlmProvider.builder().id(providerId).name("my-openai").build()));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "set_agent_llm",
                Map.of("agentId", agentId.toString(), "providerId", providerId.toString(),
                        "model", "gpt-4o"));

        verify(agentLlmService).create(eq(agentId), eq(USER_ID), eq(providerId), eq("gpt-4o"),
                eq(LlmPurpose.CHAT));
        assertEquals("CHAT", result.get("purpose"));
    }

    @Test
    @DisplayName("set_agent_llm: существующая привязка — replace (upsert)")
    void agentLlmSetReplacesWhenPresent() {
        UUID agentId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        stubOwnedAgent(agentId);
        AgentLlm binding = AgentLlm.builder()
                .id(UUID.randomUUID()).agentId(agentId).llmProviderId(providerId)
                .model("gpt-4o").purpose(LlmPurpose.VISION).build();
        when(agentLlmRepository.findAllByAgentIdOrderByPurpose(agentId)).thenReturn(List.of(binding));
        when(agentLlmService.replace(eq(agentId), eq(USER_ID), eq(LlmPurpose.VISION),
                eq(providerId), eq("gpt-4o"))).thenReturn(binding);
        when(llmProviderRepository.findById(providerId)).thenReturn(Optional.of(
                LlmProvider.builder().id(providerId).name("my-openai").build()));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "set_agent_llm",
                Map.of("agentId", agentId.toString(), "purpose", "VISION",
                        "providerId", providerId.toString(), "model", "gpt-4o"));

        verify(agentLlmService).replace(eq(agentId), eq(USER_ID), eq(LlmPurpose.VISION),
                eq(providerId), eq("gpt-4o"));
        assertEquals("VISION", result.get("purpose"));
    }

    @Test
    @DisplayName("list_llm_provider_models: search сужает до подстроки model/displayName до капа")
    void modelSearchNarrowsBeforeTheCap() {
        UUID providerId = UUID.randomUUID();
        when(llmProviderService.requireOwned(providerId, USER_ID)).thenReturn(provider(USER_ID, providerId));
        when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(providerId)).thenReturn(List.of(
                modelEntity("claude-3-5-sonnet", null),
                modelEntity("gpt-4o", "GPT-4o"),
                modelEntity("gpt-4o-mini", "GPT-4o Mini")));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "list_llm_provider_models",
                Map.of("id", providerId.toString(), "search", "gpt-4o"));

        List<?> models = (List<?>) result.get("models");
        assertEquals(2, models.size(), "только модели с подстрокой в model или displayName");
        assertEquals(false, result.get("truncated"));
    }

    @Test
    @DisplayName("get_llm_usage: снапшот сервиса отображается в окна с квотой и остатком, сводка не усечена")
    void usageSnapshotIsMapped() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID byokId = UUID.randomUUID();
        LlmUsageSnapshot snapshot = new LlmUsageSnapshot(byokId, "my-openrouter", "USER",
                List.of(new LlmUsageSnapshot.WindowUsage(
                        UsageWindow.DAY, today, 300L, 7, 1000L, 700L)));
        when(llmUsageQueryService.usageForUserSnapshot(USER_ID)).thenReturn(List.of(snapshot));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "get_llm_usage", Map.of());

        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        assertEquals(false, result.get("truncated"));
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals(byokId.toString(), item.get("providerId"));
        assertEquals("my-openrouter", item.get("providerName"));
        assertEquals("USER", item.get("source"));
        List<?> windows = (List<?>) item.get("windows");
        assertEquals(1, windows.size());
        Map<?, ?> day = (Map<?, ?>) windows.get(0);
        assertEquals("DAY", day.get("window"));
        assertEquals(today.toString(), day.get("windowStart"));
        assertEquals(300L, ((Number) day.get("usedTokens")).longValue());
        assertEquals(7L, ((Number) day.get("requests")).longValue());
        assertEquals(1000L, ((Number) day.get("limitTokens")).longValue());
        assertEquals(700L, ((Number) day.get("remainingTokens")).longValue());
    }

    @Test
    @DisplayName("list_agent_llms: привязки чужого агента — ConnectorException, репозиторий не читается")
    void listAgentLlmsOfForeignAgentIsRejected() {
        UUID foreignAgentId = UUID.randomUUID();
        when(agentRepository.findById(foreignAgentId)).thenReturn(Optional.of(
                Agent.builder().id(foreignAgentId).userId(UUID.randomUUID()).type(AgentType.GENERIC).build()));

        var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(ownerEnv(),
                "list_agent_llms", Map.of("agentId", foreignAgentId.toString())));

        assertTrue(ex.getMessage().contains("Agent not found"), ex.getMessage());
        verifyNoInteractions(agentLlmRepository);
    }

    @Test
    @DisplayName("list_agent_llms над собой — допустимо (read-only листинг, не управление)")
    void listAgentLlmsOfSelfIsAllowed() {
        when(agentRepository.findById(SELF_AGENT_ID)).thenReturn(Optional.of(
                Agent.builder().id(SELF_AGENT_ID).userId(USER_ID).type(AgentType.GENERIC).build()));
        when(agentLlmRepository.findAllByAgentIdOrderByPurpose(SELF_AGENT_ID)).thenReturn(List.of());

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(),
                "list_agent_llms", Map.of("agentId", SELF_AGENT_ID.toString()));

        assertEquals(List.of(), result.get("items"));
        verify(agentLlmRepository).findAllByAgentIdOrderByPurpose(SELF_AGENT_ID);
    }

    @Test
    @DisplayName("list_agent_llms: привязки своего агента мапятся с именем провайдера")
    void listAgentLlmsMapsBindings() {
        UUID agentId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        stubOwnedAgent(agentId);
        AgentLlm binding = AgentLlm.builder()
                .id(UUID.randomUUID())
                .agentId(agentId)
                .llmProviderId(providerId)
                .model("gpt-4o")
                .purpose(LlmPurpose.CHAT)
                .build();
        when(agentLlmRepository.findAllByAgentIdOrderByPurpose(agentId)).thenReturn(List.of(binding));
        when(llmProviderRepository.findAllByIdIn(List.of(providerId))).thenReturn(List.of(
                LlmProvider.builder().id(providerId).name("my-openai").build()));

        Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "list_agent_llms",
                Map.of("agentId", agentId.toString()));

        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("CHAT", item.get("purpose"));
        assertEquals(providerId.toString(), item.get("providerId"));
        assertEquals("my-openai", item.get("providerName"));
        assertEquals("gpt-4o", item.get("model"));
    }

    private void stubOwnedAgent(UUID agentId) {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(
                Agent.builder().id(agentId).userId(USER_ID).type(AgentType.GENERIC).build()));
    }

    private static ru.agimate.controlapi.database.entities.LlmProviderModel modelEntity(
            String model, String displayName) {
        return ru.agimate.controlapi.database.entities.LlmProviderModel.builder()
                .id(UUID.randomUUID()).model(model).displayName(displayName).build();
    }
}
