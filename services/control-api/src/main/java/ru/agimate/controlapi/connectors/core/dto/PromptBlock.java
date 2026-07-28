package ru.agimate.controlapi.connectors.core.dto;

import java.util.Map;

/**
 * A context block from a connector: a unit of content for the agent's LLM prompt.
 *
 * <p>{@code name} is the renderer's XML tag name (snake_case), {@code attrs} its attributes.
 * {@code stable} is a hint to the assembler: stable blocks go before volatile ones so the prompt
 * cache's prefix is not broken.
 *
 * @param name      block name (the renderer's tag), snake_case
 * @param placement where the block lands: the system prompt or the user turn
 * @param content   the content, with no tags or wrapper
 * @param attrs     tag attributes (e.g. the memory version); an empty map when there are none
 * @param stable    changes rarely (true) or on every run (false)
 */
public record PromptBlock(
        String name,
        Placement placement,
        String content,
        Map<String, String> attrs,
        boolean stable
) {

    public enum Placement {SYSTEM, USER}

    public static PromptBlock system(String name, String content, Map<String, String> attrs) {
        return new PromptBlock(name, Placement.SYSTEM, content, attrs, true);
    }

    public static PromptBlock user(String name, String content) {
        return new PromptBlock(name, Placement.USER, content, Map.of(), false);
    }
}
