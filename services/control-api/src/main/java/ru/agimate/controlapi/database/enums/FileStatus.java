package ru.agimate.controlapi.database.enums;

/**
 * Статус файла в файловом слое коннекторов (см. docs/connectors/files.md).
 * UPLOADING-строка старше часа считается брошенной загрузкой и удаляется чисткой.
 */
public enum FileStatus {
    UPLOADING,
    READY
}
