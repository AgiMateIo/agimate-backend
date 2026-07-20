package ru.agimate.agentworker.config;

import dev.dbos.transact.workflow.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DbosRetentionJob")
class DbosRetentionJobTest {

    private AgentProperties props;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
    }

    /** Считает вызовы вместо обращения к DBOS admin API. */
    private static class CountingJob extends DbosRetentionJob {
        final AtomicInteger purges = new AtomicInteger();
        boolean running = true;

        CountingJob(AgentProperties props) {
            super(null, props);
        }

        @Override
        boolean dbosRunning() {
            return running;
        }

        @Override
        int purgeOlderThan(Instant cutoff) {
            purges.incrementAndGet();
            return 0;
        }
    }

    @Nested
    @DisplayName("retention guard")
    class RetentionGuard {

        @Test
        @DisplayName("null retention отключает очистку")
        void nullRetentionDisables() {
            props.getDbos().setRetention(null);
            CountingJob job = new CountingJob(props);
            job.purgeExpired();
            assertEquals(0, job.purges.get());
        }

        @Test
        @DisplayName("нулевой и отрицательный retention отключают очистку")
        void zeroAndNegativeRetentionDisable() {
            props.getDbos().setRetention(Duration.ZERO);
            CountingJob job = new CountingJob(props);
            job.purgeExpired();

            props.getDbos().setRetention(Duration.ofDays(-1));
            job.purgeExpired();

            assertEquals(0, job.purges.get());
        }

        @Test
        @DisplayName("до запуска DBOS очистка не выполняется")
        void notRunningSkipsPurge() {
            props.getDbos().setRetention(Duration.ofDays(7));
            CountingJob job = new CountingJob(props);
            job.running = false;
            job.purgeExpired();
            assertEquals(0, job.purges.get());
        }

        @Test
        @DisplayName("положительный retention запускает очистку")
        void positiveRetentionPurges() {
            props.getDbos().setRetention(Duration.ofDays(7));
            CountingJob job = new CountingJob(props);
            job.purgeExpired();
            assertEquals(1, job.purges.get());
        }
    }

    @Nested
    @DisplayName("terminal states filter")
    class TerminalStates {

        @Test
        @DisplayName("двойник фильтра внутреннего GC: ровно все нежизненные статусы")
        void complementsActiveStates() {
            for (WorkflowState state : WorkflowState.values()) {
                if (state.isActive()) {
                    assertFalse(DbosRetentionJob.TERMINAL_STATES.contains(state),
                            state + " активен и не должен удаляться");
                } else {
                    assertTrue(DbosRetentionJob.TERMINAL_STATES.contains(state),
                            state + " терминален и должен попадать под ретеншн");
                }
            }
        }
    }
}
