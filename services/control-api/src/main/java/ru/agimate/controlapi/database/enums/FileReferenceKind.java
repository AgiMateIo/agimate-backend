package ru.agimate.controlapi.database.enums;

/** How a file got into a context (docs/connectors/files.md). */
public enum FileReferenceKind {

    /** The user brought it: an attachment of an incoming message. */
    INBOUND,
    /** The agent sent it: an attachment of an answer. */
    OUTBOUND,
    /** A tool produced it inside the conversation. */
    TOOL
}
