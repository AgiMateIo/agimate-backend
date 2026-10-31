package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.llm.PlatformLlmResponse;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;
import ru.agimate.controlapi.service.PlatformLlmService.Binding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformLlmService — платформенные цепочки")
class PlatformLlmServiceTest {

    @Mock
    private PlatformLlmRepository platformLlmRepository;
    @Mock
    private LlmProviderRepository llmProviderRepository;
    @Mock
    private LlmProviderService llmProviderService;

    @InjectMocks
    private PlatformLlmService service;

    private static LlmProvider platformProvider() {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name("platform")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .enabled(true)
                .build();
    }

    private static LlmProvider userProvider() {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("my-openai")
                .providerType(LlmProviderType.OPENAI)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("PUT собирает цепочку с priority по позиции и заменяет старую")
    void replaceBuildsPositionalChain() {
        LlmProvider provider = platformProvider();
        PlatformLlm old = PlatformLlm.builder()
                .llmProviderId(provider.getId()).model("old")
                .purpose(LlmPurpose.IMAGE).priority(0).build();
        when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(LlmPurpose.IMAGE))
                .thenReturn(List.of(old));
        when(platformLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<PlatformLlmResponse> chain = service.replaceChain(LlmPurpose.IMAGE, List.of(
                new Binding(provider.getId(), "first"),
                new Binding(provider.getId(), "second")));

        verify(platformLlmRepository).deleteAllInBatch(List.of(old));
        verify(llmProviderService).validateModelsKnown(provider, List.of("first"));
        verify(llmProviderService).validateModelsKnown(provider, List.of("second"));
        assertEquals(List.of("first", "second"),
                chain.stream().map(PlatformLlmResponse::model).toList());
        assertEquals(List.of(0, 1),
                chain.stream().map(PlatformLlmResponse::priority).toList());
        assertEquals("platform", chain.get(0).providerName());
    }

    @Test
    @DisplayName("пользовательский провайдер платформенным назначением не становится — 400")
    void rejectsUserProvider() {
        LlmProvider provider = userProvider();
        when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));

        assertThrows(BadRequestStatusException.class, () -> service.replaceChain(
                LlmPurpose.CHAT, List.of(new Binding(provider.getId(), "gpt-4o"))));
        verify(platformLlmRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("дубль (провайдер, модель) в цепочке — 400")
    void rejectsDuplicate() {
        LlmProvider provider = platformProvider();
        when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));

        assertThrows(BadRequestStatusException.class, () -> service.replaceChain(
                LlmPurpose.CHAT, List.of(
                        new Binding(provider.getId(), "gpt-4o"),
                        new Binding(provider.getId(), "gpt-4o"))));
        verify(platformLlmRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("цепочка длиннее пяти — 409")
    void rejectsOverlongChain() {
        UUID providerId = UUID.randomUUID();

        assertThrows(ConflictStatusException.class, () -> service.replaceChain(
                LlmPurpose.CHAT, List.of(
                        new Binding(providerId, "m0"), new Binding(providerId, "m1"),
                        new Binding(providerId, "m2"), new Binding(providerId, "m3"),
                        new Binding(providerId, "m4"), new Binding(providerId, "m5"))));
        verify(platformLlmRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("проигранная гонка replace → 409, а не исключение персистентности")
    void lostReplaceRaceIsConflict() {
        LlmProvider provider = platformProvider();
        when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(LlmPurpose.CHAT))
                .thenReturn(List.of());
        when(platformLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("uq_platform_llms_purpose_priority"))
                .when(platformLlmRepository).flush();

        assertThrows(ConflictStatusException.class, () -> service.replaceChain(
                LlmPurpose.CHAT, List.of(new Binding(provider.getId(), "gpt-4o"))));
    }

    @Test
    @DisplayName("провайдера удалили в гонке — 400 «выберите другого», а не 409 retry")
    void foreignKeyRaceIsBadRequest() {
        LlmProvider provider = platformProvider();
        when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(LlmPurpose.CHAT))
                .thenReturn(List.of());
        when(platformLlmRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("fk",
                new java.sql.SQLException("fk", "23503")))
                .when(platformLlmRepository).flush();

        assertThrows(BadRequestStatusException.class, () -> service.replaceChain(
                LlmPurpose.CHAT, List.of(new Binding(provider.getId(), "gpt-4o"))));
    }

    @Test
    @DisplayName("PUT [] на существующем назначении удаляет цепочку")
    void emptyChainDeletesPurpose() {
        LlmProvider provider = platformProvider();
        PlatformLlm old = PlatformLlm.builder()
                .llmProviderId(provider.getId()).model("old")
                .purpose(LlmPurpose.CHAT).priority(0).build();
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(LlmPurpose.CHAT))
                .thenReturn(List.of(old));
        when(platformLlmRepository.saveAll(anyList())).thenReturn(List.of());

        assertTrue(service.replaceChain(LlmPurpose.CHAT, List.of()).isEmpty());
        verify(platformLlmRepository).deleteAllInBatch(List.of(old));
    }

    @Test
    @DisplayName("DELETE на пустом назначении — 404")
    void deleteMissingPurpose() {
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(LlmPurpose.VISION))
                .thenReturn(List.of());

        assertThrows(NotFoundStatusException.class, () -> service.delete(LlmPurpose.VISION));
    }

    @Test
    @DisplayName("DELETE снимает всю цепочку назначения")
    void deletesWholeChain() {
        PlatformLlm first = PlatformLlm.builder().purpose(LlmPurpose.IMAGE).priority(0).build();
        PlatformLlm second = PlatformLlm.builder().purpose(LlmPurpose.IMAGE).priority(1).build();
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(LlmPurpose.IMAGE))
                .thenReturn(List.of(first, second));

        service.delete(LlmPurpose.IMAGE);

        verify(platformLlmRepository).deleteAllInBatch(List.of(first, second));
    }

    @Test
    @DisplayName("листинг идёт в порядке enum-назначений и priority, с именем провайдера")
    void listsInPurposeOrder() {
        LlmProvider provider = platformProvider();
        PlatformLlm vision = PlatformLlm.builder()
                .llmProviderId(provider.getId()).model("vision").purpose(LlmPurpose.VISION).priority(0).build();
        PlatformLlm chat = PlatformLlm.builder()
                .llmProviderId(provider.getId()).model("chat").purpose(LlmPurpose.CHAT).priority(0).build();
        when(platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc())
                .thenReturn(List.of(chat, vision));
        when(llmProviderRepository.findAllByIdIn(List.of(provider.getId())))
                .thenReturn(List.of(provider));

        List<PlatformLlmResponse> list = service.list();

        assertEquals(List.of(LlmPurpose.CHAT, LlmPurpose.VISION),
                list.stream().map(PlatformLlmResponse::purpose).toList());
        assertEquals("platform", list.get(0).providerName());
    }
}
