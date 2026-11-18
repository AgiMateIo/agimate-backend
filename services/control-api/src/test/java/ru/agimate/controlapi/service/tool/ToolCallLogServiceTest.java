package ru.agimate.controlapi.service.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
