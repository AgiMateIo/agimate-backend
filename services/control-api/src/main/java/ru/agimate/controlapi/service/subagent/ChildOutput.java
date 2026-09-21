package ru.agimate.controlapi.service.subagent;

import java.util.UUID;

/**
 * A child's run — a subagent's or another agent's thread — said its last word into its channel: the
 * answer, or the error that ended it. Published by the channel handler after the message is recorded;
 * the report is delivered from here.
 */
public record ChildOutput(UUID runId, boolean failed, String text) {
}
