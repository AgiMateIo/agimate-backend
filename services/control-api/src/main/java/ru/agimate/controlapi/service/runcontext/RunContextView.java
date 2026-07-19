package ru.agimate.controlapi.service.runcontext;

import java.util.List;

/**
 * Полный контекст рана для {@code GetRunContext}: упорядоченные блоки промпта (стабильные
 * первыми — prompt-cache), отскоупленные тулы, история сессии «как видел пользователь»
 * (только завершённые раны, окно и фильтр — на бэке) и вложения диалогового inbound
 * ({@code inboundParts} — только ссылки, байты воркер тянет {@code GetFile}). Воркер рендерит как есть.
 */
public record RunContextView(
        List<RunBlock> systemBlocks,
        List<RunBlock> userBlocks,
        List<RunTool> tools,
        List<RunHistoryMessage> history,
        List<InboundPart> inboundParts
) {
}
