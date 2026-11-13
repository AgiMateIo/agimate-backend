package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.database.entities.ConnectorPreset;
import ru.agimate.controlapi.database.entities.ConnectorPresetText;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.repositories.ConnectorPresetRepository;
import ru.agimate.controlapi.database.repositories.ConnectorPresetTextRepository;
import ru.agimate.controlapi.service.seed.ConnectorPresetSeed;
import ru.agimate.controlapi.service.seed.ConnectorPresetSeedReader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorPresetBootstrap — двухфазный сид пресетов")
class ConnectorPresetBootstrapTest {

    @Mock
    private ConnectorPresetSeedReader reader;
    @Mock
    private ConnectorPresetRepository repository;
    @Mock
    private ConnectorPresetTextRepository textRepository;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private IntegrationConnectorHandler handler;
    @InjectMocks
    private ConnectorPresetBootstrap bootstrap;

    @Test
    @DisplayName("существующая строка обновляется, enabled не перезаписывается")
    void updatesExistingRowAndKeepsEnabled() {
        UUID id = UUID.randomUUID();
        ConnectorPreset existing = ConnectorPreset.builder()
                .id(id)
                .code("bitrix24")
                .enabled(false)
                .sourcePresent(false)
                .build();
        when(reader.readAll()).thenReturn(List.of(seed()));
        when(repository.findByCode("bitrix24")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap.bootstrap();

        ArgumentCaptor<ConnectorPreset> saved = ArgumentCaptor.forClass(ConnectorPreset.class);
        verify(repository).save(saved.capture());
        assertEquals(id, saved.getValue().getId());
        assertEquals("Bitrix24", saved.getValue().getName());
        assertEquals("https://mcp.bitrix24.tech/mcp/", saved.getValue().getIdentity());
        assertEquals(List.of(ConnectorPresetAuth.TOKEN), saved.getValue().getAuthMethods());
        assertEquals(ConnectorPresetAvailability.AVAILABLE, saved.getValue().getAvailability());
        assertEquals(ContentCategory.WORK, saved.getValue().getCategory());
        assertTrue(saved.getValue().isSourcePresent());
        assertFalse(saved.getValue().isEnabled());
        verify(repository).hideAll(any());
    }

    @Test
    @DisplayName("переводы заменяются: старые удаляются, русская строка сохраняется")
    void replacesTranslations() {
        UUID id = UUID.randomUUID();
        ConnectorPreset existing = ConnectorPreset.builder().id(id).code("bitrix24").build();
        when(reader.readAll()).thenReturn(List.of(seed()));
        when(repository.findByCode("bitrix24")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap.bootstrap();

        verify(textRepository).deleteByPresetId(id);
        ArgumentCaptor<ConnectorPresetText> text = ArgumentCaptor.forClass(ConnectorPresetText.class);
        verify(textRepository).save(text.capture());
        assertEquals(id, text.getValue().getPresetId());
        assertEquals(ContentLanguage.RU, text.getValue().getLanguage());
        assertEquals("Битрикс24", text.getValue().getName());
        assertEquals("CRM и задачи", text.getValue().getDescription());
    }

    @Test
    @DisplayName("новая строка создаётся с source_present = true")
    void createsNewRow() {
        UUID id = UUID.randomUUID();
        when(reader.readAll()).thenReturn(List.of(seed()));
        when(repository.findByCode("bitrix24")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            ConnectorPreset preset = invocation.getArgument(0);
            preset.setId(id);
            return preset;
        });

        bootstrap.bootstrap();

        ArgumentCaptor<ConnectorPreset> saved = ArgumentCaptor.forClass(ConnectorPreset.class);
        verify(repository).save(saved.capture());
        assertEquals("bitrix24", saved.getValue().getCode());
        assertTrue(saved.getValue().isSourcePresent());
        assertTrue(saved.getValue().isEnabled());
        verify(textRepository).save(any());
    }

    @Test
    @DisplayName("пустой сид не скрывает уже засеянные строки")
    void emptySeedDoesNotHideAnything() {
        when(reader.readAll()).thenReturn(List.of());

        bootstrap.bootstrap();

        verifyNoInteractions(repository, textRepository);
        verify(reader).readAll();
        verify(repository, never()).hideAll(any());
    }

    @Test
    @DisplayName("override поля, которого нет у коннектора, — ошибка до hideAll")
    void unknownFieldOverrideFailsBeforeHide() {
        ConnectorPresetSeed seed = seed(Map.of("typo",
                new ru.agimate.controlapi.database.model.PresetFieldOverride("Typo", null, true)));
        when(reader.readAll()).thenReturn(List.of(seed));
        when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
        when(handler.getCredentialFields()).thenReturn(Map.of(
                "url", CredentialField.required("Server URL", CredentialField.Type.URL),
                "auth_token", CredentialField.optional("Bearer token", CredentialField.Type.SECRET)));

        assertThrows(IllegalStateException.class, () -> bootstrap.bootstrap());
        verify(repository, never()).hideAll(any());
    }

    @Test
    @DisplayName("bootstrap транзакционный: скрытие, upsert и переводы — одна транзакция")
    void bootstrapIsTransactional() throws NoSuchMethodException {
        assertNotNull(ConnectorPresetBootstrap.class.getMethod("bootstrap")
                .getAnnotation(Transactional.class));
    }

    private static ConnectorPresetSeed seed() {
        return seed(Map.of());
    }

    private static ConnectorPresetSeed seed(Map<String, ru.agimate.controlapi.database.model.PresetFieldOverride> fields) {
        return new ConnectorPresetSeed(
                "bitrix24",
                "mcp",
                "Bitrix24",
                "CRM and tasks",
                "Битрикс24",
                "CRM и задачи",
                "https://www.bitrix24.ru/apple-touch-icon.png",
                ContentCategory.WORK,
                "RU",
                "https://helpdesk.bitrix24.ru/open/26952788",
                List.of(ConnectorPresetAuth.TOKEN),
                ConnectorPresetAvailability.AVAILABLE,
                Map.of("url", "https://mcp.bitrix24.tech/mcp/"),
                "https://mcp.bitrix24.tech/mcp/",
                fields,
                null,
                null);
    }
}
