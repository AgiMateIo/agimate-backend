package ru.agimate.controlapi.connectors.internal.astro;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.astro.calc.Angles;
import ru.agimate.controlapi.connectors.internal.astro.calc.Aspect;
import ru.agimate.controlapi.connectors.internal.astro.calc.Aspects;
import ru.agimate.controlapi.connectors.internal.astro.calc.BirthMoment;
import ru.agimate.controlapi.connectors.internal.astro.calc.ChartAngles;
import ru.agimate.controlapi.connectors.internal.astro.calc.Ephemeris;
import ru.agimate.controlapi.connectors.internal.astro.calc.Houses;
import ru.agimate.controlapi.connectors.internal.astro.calc.LunarNode;
import ru.agimate.controlapi.connectors.internal.astro.calc.OrbPolicy;
import ru.agimate.controlapi.connectors.internal.astro.calc.PlanetPosition;
import ru.agimate.controlapi.connectors.internal.astro.calc.ZodiacSign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools of the astro connector: genuine astronomical computation (tropical zodiac, Whole Sign
 * houses, mean lunar nodes). The agent never computes positions itself — it only interprets this data.
 */
@Component
public class AstroToolService {

    private static final String NO_TIME_NOTE =
            "Birth time unknown: houses and Ascendant unavailable, Moon position is a noon estimate (±6.5°)";
    private static final String NO_PLACE_NOTE =
            "Birth place coordinates missing: houses and Ascendant unavailable";

    @Tool(name = "natal_chart",
            description = "Compute a natal (birth) chart: tropical zodiac positions of Sun..Pluto and mean "
                    + "lunar nodes, whole-sign houses, Ascendant/MC and natal aspects. All values are "
                    + "deterministic astronomical calculations — never guess them yourself.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> natalChart(
            @ToolParam("Birth date, ISO format YYYY-MM-DD") String birthDate,
            @ToolParam(value = "Local birth time HH:mm (24h). Omit if unknown — houses and Ascendant will be "
                    + "unavailable and the Moon sign may be uncertain", required = false) String birthTime,
            @ToolParam(value = "IANA timezone of the birth place, e.g. Europe/Moscow. Required together with "
                    + "birthTime; historical offsets are resolved automatically", required = false) String tzid,
            @ToolParam(value = "Birth place latitude in degrees, north positive, e.g. 55.75 for Moscow. "
                    + "City-level precision is sufficient", required = false) Double latitude,
            @ToolParam(value = "Birth place longitude in degrees, east positive, e.g. 37.62 for Moscow",
                    required = false) Double longitude) {
        Chart chart = Chart.build(birthDate, birthTime, tzid, latitude, longitude);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", chart.inputEcho(birthDate, birthTime, tzid, latitude, longitude));
        result.put("timeKnown", chart.moment.timeKnown());
        result.put("zodiacType", "tropical");
        result.put("planets", chart.planetMaps());
        result.put("lunarNodes", chart.lunarNodes());
        result.put("angles", chart.anglesMap());
        result.put("houses", chart.housesMap());
        result.put("aspects", aspectMaps(Aspects.within(chart.planets, OrbPolicy.NATAL)));
        result.put("notes", chart.notes);
        return result;
    }

    @Tool(name = "transits",
            description = "Current (or given date) planetary positions — the 'sky weather', including "
                    + "retrograde flags. If birth data is provided, also computes transit aspects to the "
                    + "natal chart. Never state planet positions or retrogrades from memory — use this tool.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> transits(
            @ToolParam(value = "Date or date-time to compute for, ISO-8601 UTC (e.g. 2026-07-17 or "
                    + "2026-07-17T09:00:00Z). Default: now", required = false) String date,
            @ToolParam(value = "Birth date YYYY-MM-DD — to compute transit aspects to the natal chart",
                    required = false) String birthDate,
            @ToolParam(value = "Local birth time HH:mm", required = false) String birthTime,
            @ToolParam(value = "IANA timezone of the birth place", required = false) String tzid,
            @ToolParam(value = "Birth place latitude, degrees", required = false) Double latitude,
            @ToolParam(value = "Birth place longitude, degrees", required = false) Double longitude) {
        Instant moment = parseMoment(date);
        List<PlanetPosition> sky = Ephemeris.positions(moment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", moment.toString());
        result.put("zodiacType", "tropical");
        result.put("planets", planetMaps(sky, null, false));
        if (birthDate != null && !birthDate.isBlank()) {
            Chart natal = Chart.build(birthDate, birthTime, tzid, latitude, longitude);
            result.put("natalPlanets", natal.planetMaps());
            result.put("transitAspects", transitAspectMaps(
                    Aspects.between(sky, natal.planets, OrbPolicy.TRANSIT)));
            result.put("notes", natal.notes);
        }
        return result;
    }

    @Tool(name = "synastry",
            description = "Compatibility (synastry) between two people: both natal charts plus inter-chart "
                    + "aspects between person A's and person B's planets. Deterministic — never guess.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> synastry(
            @ToolParam("Person A birth date YYYY-MM-DD") String firstBirthDate,
            @ToolParam(value = "Person A local birth time HH:mm", required = false) String firstBirthTime,
            @ToolParam(value = "Person A birth place IANA timezone", required = false) String firstTzid,
            @ToolParam(value = "Person A birth latitude, degrees", required = false) Double firstLatitude,
            @ToolParam(value = "Person A birth longitude, degrees", required = false) Double firstLongitude,
            @ToolParam("Person B birth date YYYY-MM-DD") String secondBirthDate,
            @ToolParam(value = "Person B local birth time HH:mm", required = false) String secondBirthTime,
            @ToolParam(value = "Person B birth place IANA timezone", required = false) String secondTzid,
            @ToolParam(value = "Person B birth latitude, degrees", required = false) Double secondLatitude,
            @ToolParam(value = "Person B birth longitude, degrees", required = false) Double secondLongitude) {
        Chart first = Chart.build(firstBirthDate, firstBirthTime, firstTzid, firstLatitude, firstLongitude);
        Chart second = Chart.build(secondBirthDate, secondBirthTime, secondTzid, secondLatitude, secondLongitude);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("first", personMap(first));
        result.put("second", personMap(second));
        result.put("interAspects", interAspectMaps(
                Aspects.between(first.planets, second.planets, OrbPolicy.SYNASTRY)));
        List<String> notes = new ArrayList<>(first.notes.stream().map(n -> "Person A: " + n).toList());
        notes.addAll(second.notes.stream().map(n -> "Person B: " + n).toList());
        result.put("notes", notes);
        return result;
    }

    // --- Assembling one chart ------------------------------------------------------------------

    /** A computed chart: positions plus (optionally) angles, along with notes about limitations of the input. */
    private record Chart(BirthMoment moment, List<PlanetPosition> planets, ChartAngles angles,
                         List<String> notes) {

        static Chart build(String birthDate, String birthTime, String tzid,
                           Double latitude, Double longitude) {
            BirthMoment moment = BirthMoment.resolve(birthDate, birthTime, tzid);
            List<PlanetPosition> planets = Ephemeris.positions(moment.utc());

            List<String> notes = new ArrayList<>();
            ChartAngles angles = null;
            if (!moment.timeKnown()) {
                notes.add(NO_TIME_NOTE);
            } else if (latitude == null || longitude == null) {
                notes.add(NO_PLACE_NOTE);
            } else {
                angles = ChartAngles.compute(moment.utc(), latitude, longitude);
            }
            return new Chart(moment, planets, angles, notes);
        }

        Map<String, Object> inputEcho(String birthDate, String birthTime, String tzid,
                                      Double latitude, Double longitude) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("birthDate", birthDate);
            input.put("birthTime", birthTime);
            input.put("tzid", tzid);
            input.put("latitude", latitude);
            input.put("longitude", longitude);
            input.put("utcDateTime", moment.utc().toString());
            return input;
        }

        List<Map<String, Object>> planetMaps() {
            return AstroToolService.planetMaps(planets, ascendantOrNull(), !moment.timeKnown());
        }

        Double ascendantOrNull() {
            return angles == null ? null : angles.ascendant();
        }

        Map<String, Object> lunarNodes() {
            double north = LunarNode.meanNorthNodeLongitude(moment.utc());
            double south = LunarNode.meanSouthNodeLongitude(moment.utc());
            Map<String, Object> nodes = new LinkedHashMap<>();
            nodes.put("type", "mean");
            nodes.put("northNode", pointMap(north, ascendantOrNull()));
            nodes.put("southNode", pointMap(south, ascendantOrNull()));
            return nodes;
        }

        Map<String, Object> anglesMap() {
            if (angles == null) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ascendant", pointMap(angles.ascendant(), null));
            map.put("midheaven", pointMap(angles.midheaven(), null));
            return map;
        }

        Map<String, Object> housesMap() {
            if (angles == null) {
                return null;
            }
            List<ZodiacSign> cusps = Houses.wholeSignCusps(angles.ascendant());
            List<Map<String, Object>> cuspMaps = new ArrayList<>();
            for (int i = 0; i < cusps.size(); i++) {
                cuspMaps.add(Map.of("house", i + 1, "sign", cusps.get(i).title()));
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("system", "whole_sign");
            map.put("cusps", cuspMaps);
            return map;
        }
    }

    private static Map<String, Object> personMap(Chart chart) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sunSign", chart.planets.getFirst().sign().title());
        map.put("moonSign", chart.planets.get(1).sign().title());
        map.put("timeKnown", chart.moment.timeKnown());
        map.put("planets", chart.planetMaps());
        return map;
    }

    // --- Mapping the computation's results ------------------------------------------------------

    private static List<Map<String, Object>> planetMaps(List<PlanetPosition> planets,
                                                        Double ascendant, boolean noonEstimate) {
        return planets.stream().map(p -> {
            Map<String, Object> map = pointMap(p.longitude(), ascendant);
            map.put("body", p.body());
            map.put("retrograde", p.retrograde());
            if (noonEstimate && p.body().equals("Moon")) {
                map.put("uncertain", true);
            }
            // body goes first, for readability
            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("body", map.remove("body"));
            ordered.putAll(map);
            return ordered;
        }).toList();
    }

    /** The common shape of a zodiac point: longitude, sign, degree within the sign, formatting, optionally the house. */
    private static Map<String, Object> pointMap(double longitude, Double ascendant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("longitude", round(longitude));
        map.put("sign", ZodiacSign.fromLongitude(longitude).title());
        map.put("degreeInSign", round(ZodiacSign.degreeInSign(longitude)));
        map.put("formatted", Angles.formatZodiac(longitude));
        if (ascendant != null) {
            map.put("house", Houses.houseOf(longitude, ascendant));
        }
        return map;
    }

    private static List<Map<String, Object>> aspectMaps(List<Aspect> aspects) {
        return aspects.stream().map(a -> Map.<String, Object>of(
                "a", a.a(), "b", a.b(), "type", a.type().title(),
                "exactAngle", a.type().angle(), "actualAngle", round(a.actualAngle()),
                "orb", round(a.orb()))).toList();
    }

    private static List<Map<String, Object>> transitAspectMaps(List<Aspect> aspects) {
        return aspects.stream().map(a -> Map.<String, Object>of(
                "transiting", a.a(), "natal", a.b(), "type", a.type().title(),
                "orb", round(a.orb()))).toList();
    }

    private static List<Map<String, Object>> interAspectMaps(List<Aspect> aspects) {
        return aspects.stream().map(a -> Map.<String, Object>of(
                "personA", a.a(), "personB", a.b(), "type", a.type().title(),
                "orb", round(a.orb()))).toList();
    }

    private static Instant parseMoment(String date) {
        if (date == null || date.isBlank()) {
            return Instant.now();
        }
        try {
            return date.contains("T")
                    ? Instant.parse(date)
                    : LocalDate.parse(date).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new ConnectorException("Invalid date '" + date + "': expected YYYY-MM-DD or ISO-8601 instant");
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
