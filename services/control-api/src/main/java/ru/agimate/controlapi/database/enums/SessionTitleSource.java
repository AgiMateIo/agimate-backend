package ru.agimate.controlapi.database.enums;

/**
 * Who wrote a session's title ({@code agent_sessions.title_source}) — all the compaction job needs to
 * know before it writes one (docs/decisions/agent-sessions-connector.md). {@code null} while the
 * session has no title at all.
 */
public enum SessionTitleSource {

    /** The placeholder cut from the first message, so the list is not empty until a real title comes. */
    HINT,

    /** Written by the {@code sessions.compact} job: after the first answer, then at every compaction. */
    GENERATED,

    /** A rename by the user; the job never writes over it. */
    USER
}
