package ru.agimate.controlapi.database.projections;

import java.util.UUID;

/** Token spend of one run: the {@code llm_usage_log} rows of its LLM calls, summed. */
public interface RunUsageProjection {
    UUID getRunId();
    long getInputTokens();
    long getOutputTokens();
    long getCacheReadTokens();
    long getCacheWriteTokens();
    long getCalls();
}
