package ru.agimate.controlapi.connectors.internal.divination.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.internal.divination.calc.DestinyMatrix.MatrixResult;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DestinyMatrix: арканы 1..22 из даты рождения")
class DestinyMatrixTest {

    @Test
    @DisplayName("Контрольный пример 14.08.1990")
    void reference() {
        MatrixResult m = DestinyMatrix.compute(LocalDate.of(1990, 8, 14));
        assertEquals(14, m.day());          // A: 14 остаётся 14 (порог 22!)
        assertEquals(8, m.month());         // B
        assertEquals(19, m.year());         // C: 1+9+9+0
        assertEquals(5, m.mission());       // D: r22(41) = 5
        assertEquals(10, m.center());       // E: r22(46) = 10
        assertEquals(22, m.paternalLine()); // F: 14+8, 22 не сворачивается
        assertEquals(9, m.maternalLine());  // G: r22(27) = 9
        assertEquals(6, m.southEast());     // H: r22(24) = 6
        assertEquals(19, m.southWest());    // I: 14+5
        assertEquals(7, m.money());         // r22(19+6=25) = 7
        assertEquals(11, m.relationships());// 5+6
        assertEquals(List.of(19, 6, 5), m.karmicTail()); // (I, r22(24), D)
    }

    @Test
    @DisplayName("Редукция r22: 22→22, 23→5, 39→12")
    void reduction() {
        assertEquals(22, DestinyMatrix.r22(22));
        assertEquals(5, DestinyMatrix.r22(23));
        assertEquals(12, DestinyMatrix.r22(39));
    }

    @Test
    @DisplayName("Граничные даты: все точки в диапазоне 1..22")
    void boundaries() {
        for (LocalDate date : List.of(LocalDate.of(2000, 1, 1), LocalDate.of(1999, 12, 31))) {
            MatrixResult m = DestinyMatrix.compute(date);
            for (int value : List.of(m.day(), m.month(), m.year(), m.mission(), m.center(),
                    m.paternalLine(), m.maternalLine(), m.southEast(), m.southWest(),
                    m.money(), m.relationships())) {
                assertTrue(value >= 1 && value <= 22, date + ": " + value);
            }
            m.karmicTail().forEach(v -> assertTrue(v >= 1 && v <= 22));
        }
    }
}
