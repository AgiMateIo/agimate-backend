package ru.agimate.controlapi.database.enums;

/**
 * What a session is a stream of work for. The scope decides how a run finds its session and,
 * through that, what shares one writer and one queue partition.
 */
public enum AgentSessionScope {
    /** A conversation in a channel. Many live sessions per channel are legal — web chat keeps its list of conversations that way. */
    CHANNEL,
    /** Everything that arrived without a channel: the events of one connection, at most one live session per agent. */
    CONNECTION
}
