package ru.agimate.controlapi.service.session.compaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.controlapi.service.session.SessionChanged;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionCompactionWriter — сводка и заголовок одной транзакцией")
class SessionCompactionWriterTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ANCHOR = UUID.randomUUID();

    @Mock private AgentRunTurnRepository turnRepository;
    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private SessionCompactionWriter writer;

    @Test
    @DisplayName("сводка легла — заголовок пишется следом")
    void summaryThenTitle() {
        when(turnRepository.insertIgnoreConflict(eq(ANCHOR), eq(SESSION_ID), eq(AGENT_ID), eq(-1), eq("SYSTEM"),
                eq("S"), isNull(), isNull(), isNull(), isNull(), eq("m"), isNull())).thenReturn(1);

        writer.write(SESSION_ID, AGENT_ID, ANCHOR, "S", "m", "T");

        verify(sessionRepository).writeGeneratedTitle(eq(SESSION_ID), eq("T"), any());
    }

    @Test
    @DisplayName("на якоре уже есть сводка — двойник опоздал, заголовок остаётся от той сводки")
    void twinLeavesTitleAlone() {
        when(turnRepository.insertIgnoreConflict(any(), any(), any(), anyInt(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any())).thenReturn(0);

        writer.write(SESSION_ID, AGENT_ID, ANCHOR, "S", "m", "T");

        verify(sessionRepository, never()).writeGeneratedTitle(any(), any(), any());
    }

    @Test
    @DisplayName("только заголовок — журнал не трогается, заголовок объявляется событием")
    void titleOnly() {
        when(sessionRepository.writeGeneratedTitle(eq(SESSION_ID), eq("T"), any())).thenReturn(1);

        writer.write(SESSION_ID, AGENT_ID, null, null, "m", "T");

        verifyNoInteractions(turnRepository);
        verify(eventPublisher).publishEvent(SessionChanged.updated(SESSION_ID));
    }

    @Test
    @DisplayName("пользователь переименовал сам — заголовок не лёг, события нет")
    void userTitleNotAnnounced() {
        when(sessionRepository.writeGeneratedTitle(eq(SESSION_ID), eq("T"), any())).thenReturn(0);

        writer.write(SESSION_ID, AGENT_ID, null, null, "m", "T");

        verifyNoInteractions(eventPublisher);
    }
}
