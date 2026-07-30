package ru.agimate.controlapi.service.llm.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.database.entities.LlmProviderCatalogEntry;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.repositories.LlmProviderCatalogRepository;
import ru.agimate.controlapi.service.seed.LlmCatalogTexts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Разделение владения между сидом и инсталляцией — единственное, ради чего у каталога вообще есть
 * таблица. Если сид начнёт писать {@code enabled}, отключённая рекомендация вернётся на ближайшем
 * деплое, и заметит это только пользователь.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LlmProviderCatalogBootstrap — сид каталога провайдеров")
class LlmProviderCatalogBootstrapTest {

    private static final List<LlmCatalogSeedEntry> SEED = LlmCatalogSeed.load();

    @Mock
    private LlmProviderCatalogRepository catalogRepository;

    private LlmProviderCatalogBootstrap bootstrap;

    private LlmProviderCatalogBootstrap bootstrap() {
        if (bootstrap == null) {
            bootstrap = new LlmProviderCatalogBootstrap(catalogRepository,
                    new LlmCatalogTexts(new ContentProperties()));
        }
        return bootstrap;
    }

    @Test
    @DisplayName("на пустой базе создаются все записи сида, включёнными")
    void seedsEveryEntry() {
        when(catalogRepository.findByCode(anyString())).thenReturn(Optional.empty());

        bootstrap().bootstrap();

        ArgumentCaptor<LlmProviderCatalogEntry> saved = ArgumentCaptor.forClass(LlmProviderCatalogEntry.class);
        verify(catalogRepository, times(SEED.size())).save(saved.capture());

        assertEquals(SEED.stream().map(LlmCatalogSeedEntry::code).toList(),
                saved.getAllValues().stream().map(LlmProviderCatalogEntry::getCode).toList());
        assertTrue(saved.getAllValues().stream().allMatch(LlmProviderCatalogEntry::isEnabled),
                "новая запись каталога должна быть предложена сразу");
    }

    @Test
    @DisplayName("содержимое существующей записи перезаписывается сидом")
    void overwritesContent() {
        LlmCatalogSeedEntry seeded = SEED.getFirst();
        LlmProviderCatalogEntry stale = LlmProviderCatalogEntry.builder()
                .id(UUID.randomUUID())
                .code(seeded.code())
                .name("Old name")
                .providerType(LlmProviderType.OPENAI)
                .baseUrl("https://stale.example/v1")
                .build();
        when(catalogRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(catalogRepository.findByCode(seeded.code())).thenReturn(Optional.of(stale));

        bootstrap().bootstrap();

        // Протухший id модели чинится деплоем — ради этого сид и владеет содержимым.
        assertEquals(seeded.name(), stale.getName());
        assertEquals(seeded.baseUrl(), stale.getBaseUrl());
        assertEquals(seeded.providerType(), stale.getProviderType());
        assertEquals(seeded.purposePriority(), stale.getPurposePriority());
        assertEquals(seeded.mediaTransport(), stale.getMediaTransport());
    }

    @Test
    @DisplayName("отключённая рекомендация остаётся отключённой")
    void keepsDisabledFlag() {
        LlmCatalogSeedEntry seeded = SEED.getFirst();
        LlmProviderCatalogEntry disabled = LlmProviderCatalogEntry.builder()
                .id(UUID.randomUUID())
                .code(seeded.code())
                .name("Old name")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .enabled(false)
                .build();
        when(catalogRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(catalogRepository.findByCode(seeded.code())).thenReturn(Optional.of(disabled));

        bootstrap().bootstrap();

        assertFalse(disabled.isEnabled(), "сид не должен возвращать отключённую запись в выдачу");
        assertEquals(seeded.name(), disabled.getName(), "содержимое при этом обновляется");
    }

    @Test
    @DisplayName("строки вне сида не читаются и не трогаются")
    void leavesForeignRowsAlone() {
        when(catalogRepository.findByCode(anyString())).thenReturn(Optional.empty());

        bootstrap().bootstrap();

        // Обход идёт по файлу, а не по таблице: корпоративный шлюз, дописанный руками, не увидят.
        SEED.forEach(entry -> verify(catalogRepository).findByCode(entry.code()));
        verify(catalogRepository, never()).findAll();
        verify(catalogRepository, never()).delete(any());
    }

    @Test
    @DisplayName("падение на одной записи не мешает остальным")
    void oneFailureDoesNotStopTheRest() {
        LlmCatalogSeedEntry first = SEED.getFirst();
        when(catalogRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(catalogRepository.findByCode(first.code())).thenThrow(new IllegalStateException("boom"));

        bootstrap().bootstrap();

        verify(catalogRepository, times(SEED.size() - 1)).save(any());
    }
}
