package ru.agimate.controlapi.database.enums;

/**
 * Кто фактически выполняет работу тула — граница доверия (покидают ли данные нашу инфраструктуру).
 * <ul>
 *   <li>{@link #BACKEND} — эффект тула живёт в нашей БД/инфре, исполняет control-api in-proc
 *       (time, memory, board, webchat, acp).</li>
 *   <li>{@link #DELEGATED} — работу выполняет внешняя система (telegram, mcp, app). Механика
 *       диспатча определяется парой с {@link TransportDirection}: OUTBOUND — мы клиент внешней
 *       системы, прокси-вызов исполняется in-proc (telegram, mcp); INBOUND — исполнитель сам
 *       подключается к нам, вызов доставляется push'ем в Centrifugo-канал {@code app:{appId}}.</li>
 *   <li>{@link #AGENT} — исполняет вызывающий агент на своей стороне (loopback, claude-code);
 *       control-api только авторизует (ABAC) и аудирует. Штатный цикл — {@code /tool/check} +
 *       {@code /tool/result}; попытка диспатча ({@code /tool/call}) отклоняется.</li>
 * </ul>
 *
 * <p>Роутинг диспатча ({@code ConnectorService.pushToConnector}) — по паре locus × direction:
 * BACKEND и DELEGATED×OUTBOUND → in-proc исполнение; DELEGATED×INBOUND → push; AGENT → отказ.
 */
public enum ExecutionLocus {
    BACKEND,
    DELEGATED,
    AGENT
}
