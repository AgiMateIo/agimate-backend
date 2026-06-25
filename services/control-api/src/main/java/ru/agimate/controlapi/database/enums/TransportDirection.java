package ru.agimate.controlapi.database.enums;

/**
 * Кто инициирует соединение с коннектором — определяет семантику секрета.
 * <ul>
 *   <li>{@link #OUTBOUND} — control-api дотягивается до внешней платформы по обратимым
 *       credentials (telegram bot token, mcp url+auth). Секрет хранится в {@code secrets}
 *       (envelope), расшифровывается на исполнении.</li>
 *   <li>{@link #INBOUND} — внешний клиент/устройство подключается к control-api и
 *       аутентифицируется невозвратным verifier'ом (app {@code key_id}/{@code key_hash}).
 *       В {@code secrets} не кладётся — хеш не расшифровывается.</li>
 * </ul>
 */
public enum TransportDirection {
    OUTBOUND,
    INBOUND
}
