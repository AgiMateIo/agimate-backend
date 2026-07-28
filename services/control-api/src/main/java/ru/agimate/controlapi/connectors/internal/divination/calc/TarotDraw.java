package ru.agimate.controlapi.connectors.internal.divination.calc;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDeck.TarotCard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/** Drawing cards: a deterministic card of the day and random spreads. */
@UtilityClass
public class TarotDraw {

    /** A drawn card with its orientation and position in the spread. */
    public record DrawnCard(TarotCard card, boolean reversed, String position) {}

    /** Spread layouts and their positions. */
    public enum Spread {
        THREE_CARD(List.of("past", "present", "future")),
        CELTIC_CROSS(List.of("present", "challenge", "subconscious", "past", "crown",
                "near_future", "self", "environment", "hopes_fears", "outcome")),
        YES_NO(List.of("answer"));

        private final List<String> positions;

        Spread(List<String> positions) {
            this.positions = positions;
        }

        public List<String> positions() {
            return positions;
        }
    }

    /**
     * The card of the day: determined by the pair (userId, date) — one user gets the same card all day
     * on any node. The seed is the first 8 bytes of SHA-256, stable across JVMs.
     */
    public static DrawnCard cardOfDay(UUID userId, LocalDate date) {
        Random random = new Random(daySeed(userId, date));
        TarotCard card = TarotDeck.card(random.nextInt(TarotDeck.SIZE));
        return new DrawnCard(card, random.nextBoolean(), "card_of_day");
    }

    /** A random spread: no repeats, and each card is reversed with 50% probability. */
    public static List<DrawnCard> draw(Spread spread) {
        List<Integer> indices = new ArrayList<>(IntStream.range(0, TarotDeck.SIZE).boxed().toList());
        Random random = ThreadLocalRandom.current();
        Collections.shuffle(indices, random);

        List<String> positions = spread.positions();
        List<DrawnCard> drawn = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            drawn.add(new DrawnCard(TarotDeck.card(indices.get(i)), random.nextBoolean(), positions.get(i)));
        }
        return drawn;
    }

    static long daySeed(UUID userId, LocalDate date) {
        byte[] digest = sha256((userId + ":" + date).getBytes(StandardCharsets.UTF_8));
        long seed = 0;
        for (int i = 0; i < 8; i++) {
            seed = (seed << 8) | (digest[i] & 0xFF);
        }
        return seed;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
