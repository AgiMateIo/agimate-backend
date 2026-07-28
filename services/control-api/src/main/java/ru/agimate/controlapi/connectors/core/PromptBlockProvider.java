package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.PromptBlock;

import java.util.List;

/**
 * A connector capability: blocks of the agent's LLM prompt. They are assembled while preparing a
 * run's context, for every active bound connection, and land either in the system prompt
 * ({@link PromptBlock.Placement#SYSTEM}) or in the user turn ({@link PromptBlock.Placement#USER});
 * tags and wrapping are applied by the renderer on the worker, and the connector supplies the
 * content alone.
 *
 * <p>The env carries {@code connectionId} (and, where applicable, {@code agentId}); credentials are
 * not decrypted for block assembly. A block must be O(1) in the connector's data volume — growing
 * listings are served by tools ({@link ToolProvider}), not by blocks.
 */
public interface PromptBlockProvider {

    List<PromptBlock> promptBlocks(ConnectorEnv env);
}
