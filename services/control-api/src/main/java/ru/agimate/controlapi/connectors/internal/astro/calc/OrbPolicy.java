package ru.agimate.controlapi.connectors.internal.astro.calc;

import java.util.Map;

/** Таблицы орбисов для разных типов расчёта. */
public enum OrbPolicy {

    /** Наталь: широкие орбисы, +2° если участвует светило (Солнце/Луна). */
    NATAL(Map.of(
            AspectType.CONJUNCTION, 8.0,
            AspectType.OPPOSITION, 8.0,
            AspectType.TRINE, 8.0,
            AspectType.SQUARE, 7.0,
            AspectType.SEXTILE, 6.0), 2.0),

    /** Транзиты: узкий плоский орбис. */
    TRANSIT(Map.of(
            AspectType.CONJUNCTION, 3.0,
            AspectType.OPPOSITION, 3.0,
            AspectType.TRINE, 3.0,
            AspectType.SQUARE, 3.0,
            AspectType.SEXTILE, 3.0), 0.0),

    /** Синастрия. */
    SYNASTRY(Map.of(
            AspectType.CONJUNCTION, 6.0,
            AspectType.OPPOSITION, 6.0,
            AspectType.TRINE, 6.0,
            AspectType.SQUARE, 6.0,
            AspectType.SEXTILE, 4.0), 0.0);

    private final Map<AspectType, Double> orbs;
    private final double luminaryBonus;

    OrbPolicy(Map<AspectType, Double> orbs, double luminaryBonus) {
        this.orbs = orbs;
        this.luminaryBonus = luminaryBonus;
    }

    double maxOrb(AspectType type, String bodyA, String bodyB) {
        double bonus = isLuminary(bodyA) || isLuminary(bodyB) ? luminaryBonus : 0.0;
        return orbs.get(type) + bonus;
    }

    private static boolean isLuminary(String body) {
        return "Sun".equals(body) || "Moon".equals(body);
    }
}
