package ru.agimate.controlapi.database.enums;

/**
 * Status of a file in the connector file layer (see docs/connectors/files.md). An UPLOADING row
 * older than an hour counts as an abandoned upload and is removed by the cleanup task.
 */
public enum FileStatus {
    UPLOADING,
    READY
}
