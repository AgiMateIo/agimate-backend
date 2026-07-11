package ru.agimate.agentworker.workers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallWorkflowImplTest {

    @Test
    @DisplayName("truncateOutput не трогает вывод в пределах лимита")
    void keepsShortOutput() {
        String output = "{\"ok\":true}";
        assertSame(output, ToolCallWorkflowImpl.truncateOutput(output, 100));
        assertSame(output, ToolCallWorkflowImpl.truncateOutput(output, output.length()));
    }

    @Test
    @DisplayName("длинный вывод обрезается до лимита с явной пометкой")
    void truncatesLongOutput() {
        String output = "x".repeat(150);
        String cut = ToolCallWorkflowImpl.truncateOutput(output, 100);
        assertTrue(cut.startsWith("x".repeat(100)));
        assertTrue(cut.contains("truncated by worker: 150 chars total, first 100 shown"));
    }

    @Test
    @DisplayName("суррогатная пара UTF-16 на границе не рвётся")
    void doesNotSplitSurrogatePair() {
        String output = "ab" + "😀".repeat(10); // 😀 = high+low surrogate
        String cut = ToolCallWorkflowImpl.truncateOutput(output, 5); // граница внутри пары
        assertTrue(cut.contains("first 4 shown"));
        assertEquals("ab😀", cut.substring(0, 4));
    }
}
