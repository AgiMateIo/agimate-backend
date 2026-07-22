package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EffectiveContext")
class EffectiveContextTest {

    @Test
    @DisplayName("без директив — ровно базовый пресет SYSTEM_TRIGGER")
    void systemTriggerDefaults() {
        EffectiveContext e = EffectiveContext.of(ContextSpec.SYSTEM_TRIGGER, null);

        assertTrue(e.loadSkillBodies());
        assertTrue(e.triggerGuidance());
        assertEquals(ContextSpec.HistoryDetail.NO_REASONING, e.historyDetail());
        assertEquals(EffectiveContext.DEFAULT_HISTORY_LIMIT, e.historyLimit());
        assertTrue(e.skillTools());
        assertFalse(e.ownConnectionTools());
        assertEquals(ContextDirectives.Presentation.EVENT, e.presentation());
        assertNull(e.guidance());
    }

    @Test
    @DisplayName("без директив — ровно базовый пресет DIALOGUE")
    void dialogueDefaults() {
        EffectiveContext e = EffectiveContext.of(ContextSpec.DIALOGUE, null);

        assertFalse(e.loadSkillBodies());
        assertFalse(e.triggerGuidance());
        assertTrue(e.skillTools());
    }

    @Test
    @DisplayName("overlay переопределяет только заданные поля")
    void overlayIsPartial() {
        ContextDirectives d = ContextDirectives.builder()
                .skillTools(false)
                .historyLimit(0)
                .build();

        EffectiveContext e = EffectiveContext.of(ContextSpec.SYSTEM_TRIGGER, d);

        assertFalse(e.skillTools());
        assertEquals(0, e.historyLimit());
        // Незаданное — из базы.
        assertTrue(e.loadSkillBodies());
        assertTrue(e.triggerGuidance());
        assertFalse(e.ownConnectionTools());
        assertEquals(ContextDirectives.Presentation.EVENT, e.presentation());
    }

    @Test
    @DisplayName("бланковый guidance нормализуется в null")
    void blankGuidanceIsNull() {
        ContextDirectives d = ContextDirectives.builder().guidance("   ").build();

        assertNull(EffectiveContext.of(ContextSpec.SYSTEM_TRIGGER, d).guidance());
    }

    @Test
    @DisplayName("PROMPT и ownConnectionTools проносятся из директив")
    void trustAndOwnToolsCarried() {
        ContextDirectives d = ContextDirectives.builder()
                .presentation(ContextDirectives.Presentation.PROMPT)
                .promptParam("prompt")
                .guidance("Сделай.")
                .ownConnectionTools(true)
                .build();

        EffectiveContext e = EffectiveContext.of(ContextSpec.SYSTEM_TRIGGER, d);

        assertEquals(ContextDirectives.Presentation.PROMPT, e.presentation());
        assertEquals("prompt", e.promptParam());
        assertEquals("Сделай.", e.guidance());
        assertTrue(e.ownConnectionTools());
    }
}
