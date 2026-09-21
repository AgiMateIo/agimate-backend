package ru.agimate.controlapi.database.projections;

import java.util.Map;

/**
 * The same run with the trigger's payload — what was actually asked. Read for one thread only:
 * instructions and context are long, and a listing has no use for them.
 */
public interface AgentRequestExchangeProjection extends AgentRequestRunProjection {
    Map<String, Object> getInput();
}
