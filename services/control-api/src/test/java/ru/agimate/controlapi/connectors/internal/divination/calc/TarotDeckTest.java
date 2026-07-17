package ru.agimate.controlapi.connectors.internal.divination.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDeck.TarotCard;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("TarotDeck: целостность датасета Райдера–Уэйта")
class TarotDeckTest {

    @Test
    @DisplayName("78 карт с уникальными id 0..77")
    void deckSize() {
        List<TarotCard> cards = TarotDeck.cards();
        assertEquals(78, cards.size());
        Set<Integer> ids = cards.stream().map(TarotCard::id).collect(Collectors.toSet());
        assertEquals(78, ids.size());
        for (int i = 0; i < 78; i++) {
            assertEquals(i, TarotDeck.card(i).id());
        }
    }

    @Test
    @DisplayName("22 старших аркана и 4 масти по 14 карт")
    void arcanaStructure() {
        List<TarotCard> cards = TarotDeck.cards();
        assertEquals(22, cards.stream().filter(c -> c.arcana().equals("major")).count());
        assertEquals(56, cards.stream().filter(c -> c.arcana().equals("minor")).count());
        for (String suit : List.of("wands", "cups", "swords", "pentacles")) {
            assertEquals(14, cards.stream().filter(c -> suit.equals(c.suit())).count(), suit);
        }
    }

    @Test
    @DisplayName("У каждой карты — имена и непустые ключевые слова обеих ориентаций")
    void cardCompleteness() {
        for (TarotCard card : TarotDeck.cards()) {
            assertNotNull(card.nameEn(), "nameEn " + card.id());
            assertNotNull(card.nameRu(), "nameRu " + card.id());
            assertFalse(card.keywordsUpright().isEmpty(), "upright " + card.id());
            assertFalse(card.keywordsReversed().isEmpty(), "reversed " + card.id());
        }
    }
}
