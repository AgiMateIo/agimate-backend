package ru.agimate.controlapi.service.trigger;

import java.util.List;
import java.util.UUID;

/**
 * Runs the stale-run sweeper has just marked FAILED — they died without a word, and their outcome
 * reaches nobody otherwise. Published inside the sweeper's transaction: listen after commit.
 */
public record RunsSwept(List<UUID> runIds) {

    public RunsSwept {
        runIds = List.copyOf(runIds);
    }
}
