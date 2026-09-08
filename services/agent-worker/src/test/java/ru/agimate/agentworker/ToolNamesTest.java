package ru.agimate.agentworker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolNamesTest {

    @Test
    @DisplayName("maps dots to __ and other unsafe chars to _")
    void sanitizes() {
        assertEquals("board__get_tasks", ToolNames.sanitize("board.get_tasks"));
        assertEquals("a_b_c", ToolNames.sanitize("a b/c"));
    }

    @Test
    @DisplayName("a taken name gets the first free numeric suffix")
    void unique() {
        assertEquals("x", ToolNames.unique(Set.of(), "x"));
        assertEquals("x_2", ToolNames.unique(Set.of("x"), "x"));
        assertEquals("x_3", ToolNames.unique(Set.of("x", "x_2"), "x"));
    }
}
