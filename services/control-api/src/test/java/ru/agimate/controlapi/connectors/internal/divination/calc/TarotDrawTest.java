package ru.agimate.controlapi.connectors.internal.divination.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDraw.DrawnCard;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDraw.Spread;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TarotDraw: карта дня и расклады")
class TarotDrawTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DATE = LocalDate.of(2026, 7, 17);

    @Test
    @DisplayName("Карта дня детерминирована: 100 вызовов — одна и та же карта и ориентация")
    void cardOfDayDeterministic() {
        DrawnCard first = TarotDraw.cardOfDay(USER, DATE);
        for (int i = 0; i < 100; i++) {
            DrawnCard again = TarotDraw.cardOfDay(USER, DATE);
            assertEquals(first.card().id(), again.card().id());
            assertEquals(first.reversed(), again.reversed());
        }
    }

    @Test
    @DisplayName("Разные пользователи и даты дают разные карты (хотя бы в части случаев)")
    void cardOfDayVaries() {
        Set<Integer> cards = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            cards.add(TarotDraw.cardOfDay(UUID.randomUUID(), DATE).card().id());
            cards.add(TarotDraw.cardOfDay(USER, DATE.plusDays(i)).card().id());
        }
        assertTrue(cards.size() > 10, "expected variety, got " + cards.size());
    }

    @Test
    @DisplayName("Расклады: 3/10/1 карт, без дубликатов, позиции в схемном порядке")
    void spreads() {
        assertEquals(3, TarotDraw.draw(Spread.THREE_CARD).size());
        assertEquals(1, TarotDraw.draw(Spread.YES_NO).size());

        List<DrawnCard> celtic = TarotDraw.draw(Spread.CELTIC_CROSS);
        assertEquals(10, celtic.size());
        Set<Integer> ids = new HashSet<>();
        celtic.forEach(c -> ids.add(c.card().id()));
        assertEquals(10, ids.size(), "no duplicate cards in a spread");
        assertEquals(Spread.CELTIC_CROSS.positions(),
                celtic.stream().map(DrawnCard::position).toList());
    }
}
