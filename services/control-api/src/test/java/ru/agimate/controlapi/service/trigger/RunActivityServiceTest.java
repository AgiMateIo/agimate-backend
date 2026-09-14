package ru.agimate.controlapi.service.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunActivityService")
class RunActivityServiceTest {

    @Mock private AgentRunRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("touch — best-effort: сбой метки не пробрасывается в RPC рана")
    void touchSwallowsFailure() {
        RunActivityService service = new RunActivityService(repository, eventPublisher);
        when(repository.touchActivity(any(), any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.touch(UUID.randomUUID()));
    }

    @Test
    @DisplayName("sweep помечает FAILED залипшие RUNNING старше порога и объявляет их")
    void sweepFailsStale() {
        RunActivityService service = new RunActivityService(repository, eventPublisher);
        List<UUID> swept = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(repository.failStaleRunning(any(), eq(RunActivityService.STALE_ERROR), any())).thenReturn(swept);

        service.sweepStaleRunning();

        verify(repository).failStaleRunning(any(LocalDateTime.class), eq(RunActivityService.STALE_ERROR),
                any(LocalDateTime.class));
        verify(eventPublisher).publishEvent(new RunsSwept(swept));
    }

    @Test
    @DisplayName("sweep без залипших ничего не объявляет")
    void sweepQuietWhenNothingStale() {
        RunActivityService service = new RunActivityService(repository, eventPublisher);
        when(repository.failStaleRunning(any(), eq(RunActivityService.STALE_ERROR), any())).thenReturn(List.of());

        service.sweepStaleRunning();

        verifyNoInteractions(eventPublisher);
    }
}
