package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.PromptBlock;

import java.util.List;

/**
 * Capability коннектора: блоки LLM-промпта агента. Собираются при подготовке контекста рана
 * по каждой активной привязанной connection и попадают в системный промпт
 * ({@link PromptBlock.Placement#SYSTEM}) или в user-ход ({@link PromptBlock.Placement#USER});
 * теги/обёртку ставит рендерер на воркере, коннектор отдаёт только содержимое.
 *
 * <p>Env несёт {@code connectionId} (и, где применимо, {@code agentId}); расшифровка
 * credentials для сборки блоков не выполняется. Блок обязан быть O(1) от объёма данных
 * коннектора — растущие листинги отдаются тулами ({@link ToolProvider}), не блоками.
 */
public interface PromptBlockProvider {

    List<PromptBlock> promptBlocks(ConnectorEnv env);
}
