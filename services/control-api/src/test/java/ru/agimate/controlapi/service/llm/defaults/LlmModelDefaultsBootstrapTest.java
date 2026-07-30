package ru.agimate.controlapi.service.llm.defaults;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.LlmModelDefaults;
import ru.agimate.controlapi.database.repositories.LlmModelDefaultsRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Перевод снапшота с INSERT-блока миграции на сид менял ровно одно поведение: обновление теперь
 * доезжает до уже засеянной инсталляции. Тесты стерегут и это, и то, чего менять не хотели —
 * записи вне файла и лишние UPDATE'ы на каждом старте.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmModelDefaultsBootstrap — сид фолбэка возможностей")
class LlmModelDefaultsBootstrapTest {

    private static final List<LlmModelDefaultsSeedEntry> SEED = LlmModelDefaultsSeed.load();

    @Mock
    private LlmModelDefaultsRepository modelDefaultsRepository;

    @InjectMocks
    private LlmModelDefaultsBootstrap bootstrap;

    @SuppressWarnings("unchecked")
    private List<LlmModelDefaults> captureSaved() {
        ArgumentCaptor<List<LlmModelDefaults>> saved = ArgumentCaptor.forClass(List.class);
        verify(modelDefaultsRepository).saveAll(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("на пустой базе записывается весь снапшот")
    void seedsEverythingOnEmptyDatabase() {
        when(modelDefaultsRepository.findAll()).thenReturn(List.of());

        bootstrap.bootstrap();

        List<LlmModelDefaults> saved = captureSaved();
        assertEquals(SEED.size(), saved.size());
        assertEquals(SEED.stream().map(LlmModelDefaultsSeedEntry::model).toList(),
                saved.stream().map(LlmModelDefaults::getModel).toList());
    }

    @Test
    @DisplayName("протухшая строка обновляется — ради этого сид и заменил INSERT в миграции")
    void refreshesStaleRow() {
        LlmModelDefaultsSeedEntry seeded = SEED.getFirst();
        LlmModelDefaults stale = LlmModelDefaults.builder()
                .id(UUID.randomUUID())
                .model(seeded.model())
                .displayName("Old name")
                .contextWindow(1)
                .build();
        when(modelDefaultsRepository.findAll()).thenReturn(List.of(stale));

        bootstrap.bootstrap();

        assertEquals(seeded.displayName(), stale.getDisplayName());
        assertEquals(seeded.contextWindow(), stale.getContextWindow());
        assertEquals(seeded.inputModalities(), stale.getInputModalities());
        assertEquals(seeded.supportedParameters(), stale.getSupportedParameters());
        assertTrue(captureSaved().contains(stale));
    }

    @Test
    @DisplayName("совпадающая строка не переписывается")
    void skipsUnchangedRow() {
        LlmModelDefaultsSeedEntry seeded = SEED.getFirst();
        LlmModelDefaults same = LlmModelDefaults.builder()
                .id(UUID.randomUUID())
                .model(seeded.model())
                .displayName(seeded.displayName())
                .contextWindow(seeded.contextWindow())
                .maxOutputTokens(seeded.maxOutputTokens())
                .inputModalities(seeded.inputModalities())
                .outputModalities(seeded.outputModalities())
                .supportedParameters(seeded.supportedParameters())
                .build();
        when(modelDefaultsRepository.findAll()).thenReturn(List.of(same));

        bootstrap.bootstrap();

        List<LlmModelDefaults> saved = captureSaved();
        assertEquals(SEED.size() - 1, saved.size(), "запись без изменений не должна попадать в UPDATE");
        assertTrue(saved.stream().noneMatch(row -> row.getModel().equals(seeded.model())));
    }

    @Test
    @DisplayName("строка вне снапшота не трогается")
    void leavesForeignRowAlone() {
        LlmModelDefaults foreign = LlmModelDefaults.builder()
                .id(UUID.randomUUID())
                .model("acme/private-model")
                .displayName("Hand-added")
                .build();
        when(modelDefaultsRepository.findAll()).thenReturn(List.of(foreign));

        bootstrap.bootstrap();

        assertEquals("Hand-added", foreign.getDisplayName());
        assertTrue(captureSaved().stream().noneMatch(row -> row.getModel().equals("acme/private-model")));
    }
}
