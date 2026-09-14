package ru.agimate.controlapi.service.subagent;

import java.util.UUID;

/**
 * A subagent's run said its last word into its channel: the answer, or the error that ended it.
 * Published by the channel handler after the message is recorded; the report is delivered from here.
 */
public record SubagentOutput(UUID runId, boolean failed, String text) {
}
