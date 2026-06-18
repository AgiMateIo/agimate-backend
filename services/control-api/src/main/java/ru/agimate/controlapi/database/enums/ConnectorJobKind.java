package ru.agimate.controlapi.database.enums;

/**
 * Кто создал строку {@code connector_jobs} и как она управляется.
 * <ul>
 *   <li>{@link #SYSTEM} — декларативная задача коннектора ({@code getJobs()}): строкой владеет
 *       reconcile-синк (upsert/удаление по бизнес-ключу {@code (connector_code, identity,
 *       name)}); уникальность бизнес-ключа в БД действует только на эти строки.
 *       {@code agent_id IS NULL}.</li>
 *   <li>{@link #AGENT} — запланирована агентом в рантайме (например {@code time.schedule});
 *       идентифицируется собственным {@code id}, на агента таких строк может быть много.
 *       {@code agent_id} — инициатор (и адресат доставки для {@code time.fire}).</li>
 *   <li>{@link #USER} — создана пользователем через manage-API (создание пока не реализовано,
 *       значение зарезервировано); {@code agent_id} — целевой агент, если задача адресная.</li>
 * </ul>
 */
public enum ConnectorJobKind {
    SYSTEM,
    USER,
    AGENT
}
