package ru.agimate.controlapi.service.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.dto.ToolResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolCallLogService")
class ToolCallLogServiceTest {

    private static final UUID LOG_ID = UUID.randomUUID();

    @Mock
    private ToolCallLogRepository toolCallLogRepository;
    @Mock
    private ConnectionRepository connectionRepository;

    @InjectMocks
    private ToolCallLogService service;

    @Nested
    @DisplayName("claimDispatch")
    class ClaimDispatch {

        @Test
        @DisplayName("один now для dispatchAt/updatedAt, дедлайн = now + таймаут")
        void claimUsesSingleTimestampAndDeadline() {
            when(toolCallLogRepository.claimDispatch(eq(LOG_ID), any(), any())).thenReturn(1);

            assertTrue(service.claimDispatch(LOG_ID, Duration.ofSeconds(120)));

            var now = ArgumentCaptor.forClass(LocalDateTime.class);
            var deadline = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(toolCallLogRepository).claimDispatch(eq(LOG_ID), now.capture(), deadline.capture());
            assertEquals(now.getValue().plusSeconds(120), deadline.getValue());
        }

        @Test
        @DisplayName("обновлённая строка — claim выигран")
        void wonClaimReturnsTrue() {
            when(toolCallLogRepository.claimDispatch(eq(LOG_ID), any(), any())).thenReturn(1);

            assertTrue(service.claimDispatch(LOG_ID, Duration.ofSeconds(120)));
        }

        @Test
        @DisplayName("строка уже захвачена — claim проигран")
        void lostClaimReturnsFalse() {
            when(toolCallLogRepository.claimDispatch(eq(LOG_ID), any(), any())).thenReturn(0);

            assertFalse(service.claimDispatch(LOG_ID, Duration.ofSeconds(120)));
        }
    }

    @Nested
    @DisplayName("isUnfinished")
    class IsUnfinished {

        @Test
        @DisplayName("незавершённая строка — true")
        void unfinishedRow() {
            when(toolCallLogRepository.existsByIdAndFinishAtIsNull(LOG_ID)).thenReturn(true);

            assertTrue(service.isUnfinished(LOG_ID));
        }

        @Test
        @DisplayName("завершённая строка — false")
        void finishedRow() {
            when(toolCallLogRepository.existsByIdAndFinishAtIsNull(LOG_ID)).thenReturn(false);

            assertFalse(service.isUnfinished(LOG_ID));
        }
    }

    @Nested
    @DisplayName("finishNonReplayable")
    class FinishNonReplayable {

        @Test
        @DisplayName("один now для finishAt/updatedAt, output и error — из результата")
        void finishUsesSingleTimestampAndResult() {
            var result = new ToolResult("ext-1", "ssh", "stdout", "boom");
            when(toolCallLogRepository.finishNonReplayable(eq(LOG_ID), any(), eq("stdout"), eq("boom")))
                    .thenReturn(1);

            assertTrue(service.finishNonReplayable(LOG_ID, result));

            var now = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(toolCallLogRepository)
                    .finishNonReplayable(eq(LOG_ID), now.capture(), eq("stdout"), eq("boom"));
            LocalDateTime captured = now.getValue();
            assertTrue(captured.isAfter(LocalDateTime.now().minusMinutes(1)));
        }

        @Test
        @DisplayName("ошибка finalize-ится с null output")
        void errorResultWritesNullOutput() {
            var result = new ToolResult("ext-1", "ssh", null, "boom");
            when(toolCallLogRepository.finishNonReplayable(eq(LOG_ID), any(), isNull(), eq("boom")))
                    .thenReturn(1);

            assertTrue(service.finishNonReplayable(LOG_ID, result));

            verify(toolCallLogRepository).finishNonReplayable(eq(LOG_ID), any(), isNull(), eq("boom"));
        }

        @Test
        @DisplayName("строка уже завершена — CAS проигран, false")
        void lostCasReturnsFalse() {
            when(toolCallLogRepository.finishNonReplayable(eq(LOG_ID), any(), any(), any())).thenReturn(0);

            assertFalse(service.finishNonReplayable(
                    LOG_ID, new ToolResult("ext-1", "ssh", "stdout", null)));
        }
    }

    @Nested
    @DisplayName("findRecoverableDispatches")
    class FindRecoverableDispatches {

        @Test
        @DisplayName("берёт первую страницу размером с пачку")
        void firstBatchPage() {
            var page = PageRequest.of(0, 100);
            when(toolCallLogRepository.findRecoverableDispatches(any(), eq(page))).thenReturn(List.of());

            assertTrue(service.findRecoverableDispatches(LocalDateTime.of(2026, 11, 18, 12, 0), 100).isEmpty());

            var captured = ArgumentCaptor.forClass(Pageable.class);
            verify(toolCallLogRepository).findRecoverableDispatches(any(), captured.capture());
            assertEquals(page, captured.getValue());
            assertTrue(captured.getValue().getSort().isUnsorted());
        }
    }
}
