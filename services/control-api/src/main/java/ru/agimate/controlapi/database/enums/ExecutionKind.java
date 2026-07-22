package ru.agimate.controlapi.database.enums;

/**
 * Вид исполнения тулов коннектора ({@code connectors.execution_kind}) — единственная ось,
 * по которой ветвится диспатч вызова ({@code ConnectorService.pushToConnector}).
 *
 * <ul>
 *   <li>{@link #BACKEND} — исполняет наша инфраструктура in-proc ({@code @Tool}-метод хендлера).
 *       Сюда входят и коннекторы, ходящие во внешние API изнутри (telegram, mcp, media):
 *       «внешний по сети» — деталь реализации тул-сервиса, не ось модели.</li>
 *   <li>{@link #DEVICE} — исполняет устройство (app): вызов доставляется push'ем в канал
 *       устройства, результат приходит асинхронно.</li>
 *   <li>{@link #LOOPBACK} — исполняет сам вызывающий агент (claude-code): диспатч сюда — ошибка,
 *       агент забирает вызовы циклом {@code /tool/check} + {@code /tool/result}.</li>
 * </ul>
 *
 * <p>Бывшая пара {@code execution_locus × transport_direction} кодировала эти же три случая
 * (BACKEND и DELEGATED×OUTBOUND диспатчились одинаково); различие «наша инфра vs внешняя
 * платформа» — информационное и живёт в доках, не в данных.
 */
public enum ExecutionKind {
    BACKEND,
    DEVICE,
    LOOPBACK
}
