package ru.agimate.controlapi.connectors.internal.divination.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Numerology: число жизненного пути с мастер-числами")
class NumerologyTest {

    @Test
    @DisplayName("14.08.1990 → 5 (5+8+1=14→5)")
    void plainLifePath() {
        assertEquals(5, Numerology.lifePath(LocalDate.of(1990, 8, 14)));
    }

    @Test
    @DisplayName("29.11.1993: компоненты — мастер-числа {11, 11, 22}, итог 44→8")
    void masterComponents() {
        LocalDate date = LocalDate.of(1993, 11, 29);
        assertEquals(11, Numerology.lifePathDay(date));   // 29 → 11, мастер — стоп
        assertEquals(11, Numerology.lifePathMonth(date));
        assertEquals(22, Numerology.lifePathYear(date));  // 1993 → 22, мастер — стоп
        assertEquals(8, Numerology.lifePath(date));       // 44 → 8 (44 не мастер)
        assertEquals(11, Numerology.birthdayNumber(date));
        assertTrue(Numerology.isMaster(11));
        assertFalse(Numerology.isMaster(8));
    }

    @Test
    @DisplayName("Правило редукции отличается от матричного: 48 → 3, а r22(48) → 12")
    void differsFromMatrixReduction() {
        assertEquals(3, Numerology.reduceKeepMaster(48));
        assertEquals(12, DestinyMatrix.r22(48));
        assertEquals(11, Numerology.reduceKeepMaster(29));
        assertEquals(22, Numerology.reduceKeepMaster(22));
    }

    @Test
    @DisplayName("Персональный год")
    void personalYear() {
        // 14.08 в 2026: 5 + 8 + reduce(2+0+2+6=10→1) = 14 → 5
        assertEquals(5, Numerology.personalYear(LocalDate.of(1990, 8, 14), 2026));
    }
}
