package ru.agimate.controlapi.service.llm.defaults;

import java.util.List;

/**
 * One entry of {@code seed/llm-models.yaml} as read from disk. Every field but {@code model} is
 * nullable — the snapshot carries whatever the source listing reported, and a gap means «unknown»,
 * which is exactly how the fallback treats it.
 *
 * @param model the model's id at the provider, and the key the fallback is looked up by
 */
public record LlmModelDefaultsSeedEntry(
        String model,
        String displayName,
        Integer contextWindow,
        Integer maxOutputTokens,
        List<String> inputModalities,
        List<String> outputModalities,
        List<String> supportedParameters
) {
}
