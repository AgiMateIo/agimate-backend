package ru.agimate.controlapi.connectors.internal.divination;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.divination.calc.DestinyMatrix;
import ru.agimate.controlapi.connectors.internal.divination.calc.DestinyMatrix.MatrixResult;
import ru.agimate.controlapi.connectors.internal.divination.calc.Numerology;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDraw;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDraw.DrawnCard;
import ru.agimate.controlapi.connectors.internal.divination.calc.TarotDraw.Spread;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools of the divination connector: the Destiny Matrix, numerology and Tarot. Every number and every
 * drawn card is determined by the engine — the agent does not invent them, it interprets them.
 */
@Component
public class DivinationToolService {

    @Tool(name = "matrix_of_destiny",
            description = "Compute the Matrix of Destiny energies from a birth date. Returns arcana "
                    + "numbers 1..22 for every point — deterministic arithmetic, never compute yourself.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> matrixOfDestiny(
            @ToolParam("Birth date YYYY-MM-DD") String birthDate) {
        MatrixResult m = DestinyMatrix.compute(parseDate(birthDate));

        Map<String, Object> points = new LinkedHashMap<>();
        points.put("day", m.day());
        points.put("month", m.month());
        points.put("year", m.year());
        points.put("mission", m.mission());
        points.put("center", m.center());
        points.put("paternalLine", m.paternalLine());
        points.put("maternalLine", m.maternalLine());
        points.put("southEast", m.southEast());
        points.put("southWest", m.southWest());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("birthDate", birthDate);
        result.put("points", points);
        result.put("moneyLine", Map.of("money", m.money(), "relationships", m.relationships()));
        result.put("karmicTail", m.karmicTail());
        result.put("range", "arcana 1..22");
        return result;
    }

    @Tool(name = "numerology",
            description = "Numerology from birth date: Life Path number (master numbers 11/22/33 preserved), "
                    + "birthday number and current personal year. Deterministic — never compute yourself.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> numerology(
            @ToolParam("Birth date YYYY-MM-DD") String birthDate) {
        LocalDate date = parseDate(birthDate);
        int lifePath = Numerology.lifePath(date);
        int currentYear = LocalDate.now(ZoneOffset.UTC).getYear();
        int personalYear = Numerology.personalYear(date, currentYear);

        Map<String, Object> lifePathMap = new LinkedHashMap<>();
        lifePathMap.put("value", lifePath);
        lifePathMap.put("isMaster", Numerology.isMaster(lifePath));
        lifePathMap.put("components", Map.of(
                "day", Numerology.lifePathDay(date),
                "month", Numerology.lifePathMonth(date),
                "year", Numerology.lifePathYear(date)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("birthDate", birthDate);
        result.put("lifePath", lifePathMap);
        result.put("birthdayNumber", Numerology.birthdayNumber(date));
        result.put("personalYear", Map.of("year", currentYear, "value", personalYear));
        return result;
    }

    @Tool(name = "tarot_card_of_day",
            description = "The user's tarot card of the day. Deterministic: the same user gets the same "
                    + "card for the whole day — always call this instead of picking a card yourself.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> tarotCardOfDay(
            @ToolParam(value = "Date YYYY-MM-DD (UTC). Default: today", required = false) String date) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.userId() == null) {
            throw new ConnectorException("tarot_card_of_day requires a user context");
        }
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(ZoneOffset.UTC) : parseDate(date);
        DrawnCard drawn = TarotDraw.cardOfDay(ctx.userId(), day);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());
        result.put("card", cardMap(drawn));
        result.put("sameForWholeDay", true);
        return result;
    }

    @Tool(name = "tarot_draw_spread",
            description = "Draw a random tarot spread from the full 78-card Rider-Waite deck (no duplicates, "
                    + "each card may be reversed). Always draw via this tool — never invent which cards came up.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false)) // random — not idempotent
    public Map<String, Object> tarotDrawSpread(
            @ToolParam("Spread type: THREE_CARD (past/present/future), CELTIC_CROSS (10 cards), "
                    + "YES_NO (1 card)") Spread spread,
            @ToolParam(value = "The question being asked (echoed back for context)", required = false)
            String question) {
        List<Map<String, Object>> cards = TarotDraw.draw(spread).stream()
                .map(DivinationToolService::cardMap)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spread", spread.name());
        result.put("question", question);
        result.put("cards", cards);
        return result;
    }

    /** A card with its position, orientation and the keywords of that orientation. */
    private static Map<String, Object> cardMap(DrawnCard drawn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("position", drawn.position());
        map.put("nameEn", drawn.card().nameEn());
        map.put("nameRu", drawn.card().nameRu());
        map.put("arcana", drawn.card().arcana());
        map.put("number", drawn.card().number());
        map.put("suit", drawn.card().suit());
        map.put("reversed", drawn.reversed());
        map.put("keywords", drawn.reversed() ? drawn.card().keywordsReversed()
                : drawn.card().keywordsUpright());
        return map;
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ConnectorException("Invalid date '" + date + "': expected YYYY-MM-DD");
        }
    }
}
