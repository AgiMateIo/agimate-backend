package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.controller.manage.dto.PatchAgenticTeamRequest;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgenticTeamService.patch — три состояния поля")
class AgenticTeamServiceTest {

    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private AgenticTeamRepository agenticTeamRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private BoardRepository boardRepository;

    @InjectMocks
    private AgenticTeamService service;

    private final AgenticTeam team = AgenticTeam.builder()
            .id(TEAM_ID).userId(USER_ID).name("команда").description("описание").build();

    @BeforeEach
    void setUp() {
        when(agenticTeamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(agenticTeamRepository.save(any(AgenticTeam.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("имя не пришло → проверка на дубль не выполняется вовсе")
    void nameClashIsNotCheckedWhenNameIsAbsent() {
        service.patch(TEAM_ID, USER_ID, new PatchAgenticTeamRequest(null, "новое описание"));

        verify(agenticTeamRepository, never()).existsByUserIdAndName(any(), any());
        assertEquals("команда", team.getName());
        assertEquals("новое описание", team.getDescription());
    }

    @Test
    @DisplayName("пустая строка → описание очищается")
    void blankDescriptionIsCleared() {
        service.patch(TEAM_ID, USER_ID, new PatchAgenticTeamRequest(null, ""));

        assertNull(team.getDescription());
    }

    @Test
    @DisplayName("занятое имя → 400")
    void takenNameRejected() {
        when(agenticTeamRepository.existsByUserIdAndName(USER_ID, "чужая")).thenReturn(true);

        assertThrows(BadRequestStatusException.class,
                () -> service.patch(TEAM_ID, USER_ID, new PatchAgenticTeamRequest("чужая", null)));

        assertEquals("команда", team.getName());
    }

    @Test
    @DisplayName("пустое имя → 400, команда без имени не бывает")
    void blankNameRejected() {
        assertThrows(ValidationErrorStatusException.class,
                () -> service.patch(TEAM_ID, USER_ID, new PatchAgenticTeamRequest("  ", null)));

        assertEquals("команда", team.getName());
    }
}
