package ru.agimate.controlapi.service.runcontext;

import java.util.List;

/**
 * Полный контекст рана для {@code GetRunContext}: упорядоченные блоки промпта (стабильные
 * первыми — prompt-cache) и отскоупленные тулы. Воркер рендерит блоки как есть,
 * не пересортировывая.
 */
public record RunContextView(
        List<RunBlock> systemBlocks,
        List<RunBlock> userBlocks,
        List<RunTool> tools
) {
}
