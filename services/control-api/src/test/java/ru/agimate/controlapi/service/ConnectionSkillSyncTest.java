package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.controller.app.dto.SkillDeclaration;
import ru.agimate.controlapi.database.entities.ConnectionSkill;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.enums.SkillUpdatedBy;
import ru.agimate.controlapi.database.repositories.ConnectionSkillRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionSkillSync — объявления и копии приложения")
class ConnectionSkillSyncTest {

    private static final UUID CONNECTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private ConnectionSkillRepository connectionSkillRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillService skillService;

    @InjectMocks
    private ConnectionSkillSync sync;

    @Test
    @DisplayName("создаёт объявление и копию на каждый ключ карты")
    void createsDeclarationAndCopyPerKey() {
        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID)).thenReturn(List.of());
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> {
            ConnectionSkill connectionSkill = invocation.getArgument(0);
            connectionSkill.setId(UUID.randomUUID());
            return connectionSkill;
        });
        when(skillRepository.findByConnectionSkillId(any())).thenReturn(Optional.empty());
        when(skillRepository.existsByKeyNotDeleted(any(), any(), isNull())).thenReturn(false);

        Map<String, SkillDeclaration> declarations = new LinkedHashMap<>();
        declarations.put("screenshot", declaration("Screenshots", "Снимает экран", "Инструкция скриншота"));
        declarations.put("clipboard", declaration(null, "Буфер обмена", "Инструкция буфера"));
        sync.sync(CONNECTION_ID, USER_ID, "desktop", "AgiMate Desktop", declarations, true);

        ArgumentCaptor<Skill> copies = ArgumentCaptor.forClass(Skill.class);
        verify(skillRepository, times(2)).save(copies.capture());
        assertEquals(List.of("app-desktop-screenshot", "app-desktop-clipboard"),
                copies.getAllValues().stream().map(Skill::getName).toList());
        Skill screenshot = copies.getAllValues().get(0);
        assertNotNull(screenshot.getConnectionSkillId());
        assertEquals(USER_ID, screenshot.getUserId());
        assertEquals(SkillUpdatedBy.APP, screenshot.getUpdatedBy());
        assertEquals(Disclosure.LAZY, screenshot.getDisclosure());
        assertEquals(ContentCategory.PLATFORM, screenshot.getCategory());
        assertEquals(List.of("app"), screenshot.getConnectorCodes());
        assertEquals(List.of("Сделай скриншот"), screenshot.getStarters());
        assertNull(screenshot.getOriginConnectionId());
        assertEquals("Screenshots", screenshot.getTitle());
    }

    @Test
    @DisplayName("обновляет копию только пока updated_by = APP")
    void refreshesOnlyWhileApp() {
        ConnectionSkill existing = connectionSkill("screenshot", "Старое", "Старое тело");
        Skill copy = copy(existing.getId(), "app-desktop-screenshot", SkillUpdatedBy.APP);
        copy.setVersion(3);

        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(existing));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillRepository.findByConnectionSkillId(existing.getId())).thenReturn(Optional.of(copy));

        sync.sync(CONNECTION_ID, USER_ID, "desktop", "AgiMate Desktop", Map.of(
                "screenshot", declaration("Новое имя", "Новое описание", "Новое тело")), true);

        assertEquals("Новое имя", copy.getTitle());
        assertEquals("Новое описание", copy.getDescription());
        assertEquals("Новое тело", copy.getMdContent());
        assertEquals(4, copy.getVersion());
        verify(skillRepository).save(copy);
    }

    @Test
    @DisplayName("не трогает копию после правки пользователем")
    void doesNotRefreshUserEditedCopy() {
        ConnectionSkill existing = connectionSkill("screenshot", "Старое", "Старое тело");
        Skill copy = copy(existing.getId(), "app-desktop-screenshot", SkillUpdatedBy.USER);
        copy.setTitle("Моё имя");
        copy.setVersion(5);

        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(existing));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillRepository.findByConnectionSkillId(existing.getId())).thenReturn(Optional.of(copy));

        sync.sync(CONNECTION_ID, USER_ID, "desktop", null, Map.of(
                "screenshot", declaration(null, "Новое описание", "Новое тело")), true);

        assertEquals("Моё имя", copy.getTitle());
        assertEquals(5, copy.getVersion());
        verify(skillRepository, never()).save(any());
    }

    @Test
    @DisplayName("не воскрешает копию, удалённую пользователем")
    void neverResurrectsDeletedCopy() {
        ConnectionSkill existing = connectionSkill("screenshot", "Описание", "Тело");
        Skill deleted = copy(existing.getId(), "app-desktop-screenshot", SkillUpdatedBy.APP);
        deleted.setDeletedAt(LocalDateTime.now());

        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(existing));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillRepository.findByConnectionSkillId(existing.getId())).thenReturn(Optional.of(deleted));

        sync.sync(CONNECTION_ID, USER_ID, "desktop", null, Map.of(
                "screenshot", declaration(null, "Описание", "Тело")), true);

        verify(skillRepository, never()).save(any());
    }

    @Test
    @DisplayName("исчезнувший ключ гасит объявление, но оставляет копию живой")
    void removalFreezesCopyInsteadOfDeletingIt() {
        ConnectionSkill existing = connectionSkill("screenshot", "Описание", "Тело");
        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(existing));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        sync.sync(CONNECTION_ID, USER_ID, "desktop", null, Map.of(), true);

        assertNotNull(existing.getDeletedAt());
        verify(connectionSkillRepository).save(existing);
        verifyNoInteractions(skillService);
        verify(skillRepository, never()).save(any());
    }

    @Test
    @DisplayName("усыновляет legacy origin-копию вместо создания второй")
    void adoptsLegacyOriginCopy() {
        ConnectionSkill existing = connectionSkill("default", "Старое", "Старое тело");
        Skill legacy = Skill.builder()
                .id(UUID.randomUUID())
                .name("app-desktop")
                .originConnectionId(CONNECTION_ID)
                .updatedBy(SkillUpdatedBy.APP)
                .version(2)
                .build();

        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(existing));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skillRepository.findByConnectionSkillId(existing.getId())).thenReturn(Optional.empty());
        when(skillRepository.findByOriginConnectionId(CONNECTION_ID)).thenReturn(Optional.of(legacy));
        when(skillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        sync.sync(CONNECTION_ID, USER_ID, "desktop", null, Map.of(
                "default", declaration(null, "Новое описание", "Новое тело")), false);

        assertEquals(existing.getId(), legacy.getConnectionSkillId());
        assertEquals("Новое описание", legacy.getDescription());
        assertEquals(3, legacy.getVersion());
        verify(skillRepository, times(2)).save(legacy);
    }

    @Test
    @DisplayName("legacy default сохраняет имя app-<code> и origin")
    void legacyDefaultKeepsNameAndOrigin() {
        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID)).thenReturn(List.of());
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> {
            ConnectionSkill connectionSkill = invocation.getArgument(0);
            connectionSkill.setId(UUID.randomUUID());
            return connectionSkill;
        });
        when(skillRepository.findByConnectionSkillId(any())).thenReturn(Optional.empty());
        when(skillRepository.findByOriginConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());
        when(skillRepository.existsByKeyNotDeleted(any(), any(), isNull())).thenReturn(false);

        sync.sync(CONNECTION_ID, USER_ID, "desktop", null, Map.of(
                "default", declaration(null, "Описание", "Тело")), false);

        ArgumentCaptor<Skill> copy = ArgumentCaptor.forClass(Skill.class);
        verify(skillRepository).save(copy.capture());
        assertEquals("app-desktop", copy.getValue().getName());
        assertEquals("desktop", copy.getValue().getTitle());
        assertEquals(CONNECTION_ID, copy.getValue().getOriginConnectionId());
    }

    @Test
    @DisplayName("удаление приложения пропускает уже удалённую копию")
    void deletionSkipsAlreadyDeletedCopy() {
        ConnectionSkill connectionSkill = connectionSkill("screenshot", "Описание", "Тело");
        Skill deleted = copy(connectionSkill.getId(), "app-desktop-screenshot", SkillUpdatedBy.APP);
        deleted.setDeletedAt(LocalDateTime.now());

        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(connectionSkill));
        when(skillRepository.findByOriginConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());
        when(skillRepository.findByConnectionSkillId(connectionSkill.getId())).thenReturn(Optional.of(deleted));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        sync.deleteForConnection(CONNECTION_ID, USER_ID);

        verifyNoInteractions(skillService);
        assertNotNull(connectionSkill.getDeletedAt());
        verify(connectionSkillRepository).save(connectionSkill);
    }

    @Test
    @DisplayName("удаление приложения удаляет живые копии")
    void deletionRemovesLiveCopies() {
        ConnectionSkill connectionSkill = connectionSkill("screenshot", "Описание", "Тело");
        Skill live = copy(connectionSkill.getId(), "app-desktop-screenshot", SkillUpdatedBy.APP);

        when(connectionSkillRepository.findByConnectionIdOrderByNameAsc(CONNECTION_ID))
                .thenReturn(List.of(connectionSkill));
        when(skillRepository.findByOriginConnectionId(CONNECTION_ID)).thenReturn(Optional.empty());
        when(skillRepository.findByConnectionSkillId(connectionSkill.getId())).thenReturn(Optional.of(live));
        when(connectionSkillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        sync.deleteForConnection(CONNECTION_ID, USER_ID);

        verify(skillService).delete(live.getId(), USER_ID, false);
    }

    @Test
    @DisplayName("больше 32 навыков у приложения — 400")
    void rejectsMoreThanLimit() {
        Map<String, SkillDeclaration> declarations = new LinkedHashMap<>();
        for (int i = 0; i <= ConnectionSkillSync.MAX_SKILLS; i++) {
            declarations.put("skill-" + i, declaration(null, "Описание", "Тело"));
        }

        assertThrows(BadRequestStatusException.class,
                () -> sync.sync(CONNECTION_ID, USER_ID, "desktop", null, declarations, true));
        verify(connectionSkillRepository, never()).findByConnectionIdOrderByNameAsc(any());
    }

    private static SkillDeclaration declaration(String title, String description, String body) {
        return new SkillDeclaration(title, description, body, List.of("Сделай скриншот"), null);
    }

    private static ConnectionSkill connectionSkill(String key, String description, String content) {
        return ConnectionSkill.builder()
                .id(UUID.randomUUID())
                .connectionId(CONNECTION_ID)
                .uri("app://" + CONNECTION_ID + "/" + key)
                .name(key)
                .title("Старое имя")
                .description(description)
                .content(content)
                .starters(List.of())
                .build();
    }

    private static Skill copy(UUID connectionSkillId, String name, SkillUpdatedBy updatedBy) {
        return Skill.builder()
                .id(UUID.randomUUID())
                .name(name)
                .title("Старое имя")
                .description("Старое описание")
                .mdContent("Старое тело")
                .connectionSkillId(connectionSkillId)
                .updatedBy(updatedBy)
                .version(1)
                .build();
    }
}
