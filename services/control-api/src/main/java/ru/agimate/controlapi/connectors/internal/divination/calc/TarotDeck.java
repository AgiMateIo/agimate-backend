package ru.agimate.controlapi.connectors.internal.divination.calc;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** The Rider–Waite deck from a classpath dataset: 78 cards, ids 0..77. */
@UtilityClass
public class TarotDeck {

    public static final int SIZE = 78;

    private static final String DATASET = "datasets/tarot/rider_waite.json";

    /**
     * A card of the deck.
     *
     * @param number for the major arcana 0..21; for the minor 1..14 (11 Page, 12 Knight, 13 Queen, 14 King)
     * @param suit   wands|cups|swords|pentacles, null for the major arcana
     */
    public record TarotCard(int id, String nameEn, String nameRu, String arcana, int number, String suit,
                            List<String> keywordsUpright, List<String> keywordsReversed) {}

    private record Dataset(String deck, List<TarotCard> cards) {}

    private static final List<TarotCard> CARDS = load();

    public static List<TarotCard> cards() {
        return CARDS;
    }

    public static TarotCard card(int id) {
        return CARDS.get(id);
    }

    private static List<TarotCard> load() {
        try (InputStream in = new ClassPathResource(DATASET).getInputStream()) {
            Dataset dataset = JsonUtils.MAPPER.readValue(in, Dataset.class);
            if (dataset.cards().size() != SIZE) {
                throw new IllegalStateException(
                        "Tarot dataset must contain %d cards, got %d".formatted(SIZE, dataset.cards().size()));
            }
            return List.copyOf(dataset.cards());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load tarot dataset " + DATASET, e);
        }
    }
}
