package ru.agimate.controlapi.connectors.internal.astro.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Aspects: поиск мажорных аспектов с орбисами")
class AspectsTest {

    private static PlanetPosition at(String body, double lon) {
        return new PlanetPosition(body, lon, 0, false);
    }

    @Test
    @DisplayName("10° и 130° → трин (орбис 0)")
    void exactTrine() {
        Optional<Aspect> aspect = Aspects.find(at("Mercury", 10), at("Venus", 130), OrbPolicy.NATAL);
        assertTrue(aspect.isPresent());
        assertEquals(AspectType.TRINE, aspect.get().type());
        assertEquals(0.0, aspect.get().orb(), 1e-9);
    }

    @Test
    @DisplayName("Орбис 8.5°: соединение есть для Солнце-Луна (+2°), нет для Меркурий-Венера")
    void luminaryBonus() {
        assertTrue(Aspects.find(at("Sun", 0), at("Moon", 8.5), OrbPolicy.NATAL).isPresent());
        assertTrue(Aspects.find(at("Mercury", 0), at("Venus", 8.5), OrbPolicy.NATAL).isEmpty());
    }

    @Test
    @DisplayName("Переход через 360°: 356° и 2° → соединение (сепарация 6°)")
    void wrapAround() {
        Optional<Aspect> aspect = Aspects.find(at("Mars", 356), at("Jupiter", 2), OrbPolicy.NATAL);
        assertTrue(aspect.isPresent());
        assertEquals(AspectType.CONJUNCTION, aspect.get().type());
        assertEquals(6.0, aspect.get().actualAngle(), 1e-9);
    }

    @Test
    @DisplayName("Транзитная политика режет орбис больше 3° (и без бонуса светил)")
    void transitPolicy() {
        assertTrue(Aspects.find(at("Sun", 0), at("Saturn", 92.9), OrbPolicy.TRANSIT).isPresent());
        assertTrue(Aspects.find(at("Sun", 0), at("Saturn", 93.5), OrbPolicy.TRANSIT).isEmpty());
    }

    @Test
    @DisplayName("within: каждая пара проверяется один раз")
    void withinPairs() {
        List<Aspect> aspects = Aspects.within(
                List.of(at("Sun", 0), at("Moon", 120), at("Mars", 240)), OrbPolicy.NATAL);
        assertEquals(3, aspects.size()); // три трина по кругу
        assertTrue(aspects.stream().allMatch(a -> a.type() == AspectType.TRINE));
    }
}
