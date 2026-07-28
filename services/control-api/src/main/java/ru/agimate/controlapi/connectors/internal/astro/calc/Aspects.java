package ru.agimate.controlapi.connectors.internal.astro.calc;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Search for major aspects between bodies. */
@UtilityClass
public class Aspects {

    /** Aspects within a single chart (each pair once). */
    public static List<Aspect> within(List<PlanetPosition> chart, OrbPolicy policy) {
        List<Aspect> aspects = new ArrayList<>();
        for (int i = 0; i < chart.size(); i++) {
            for (int j = i + 1; j < chart.size(); j++) {
                find(chart.get(i), chart.get(j), policy).ifPresent(aspects::add);
            }
        }
        return aspects;
    }

    /** Aspects between the bodies of chart A and chart B (all A×B pairs). */
    public static List<Aspect> between(List<PlanetPosition> a, List<PlanetPosition> b, OrbPolicy policy) {
        List<Aspect> aspects = new ArrayList<>();
        for (PlanetPosition pa : a) {
            for (PlanetPosition pb : b) {
                find(pa, pb, policy).ifPresent(aspects::add);
            }
        }
        return aspects;
    }

    static Optional<Aspect> find(PlanetPosition a, PlanetPosition b, OrbPolicy policy) {
        double separation = Angles.separation(a.longitude(), b.longitude());
        for (AspectType type : AspectType.values()) {
            double orb = Math.abs(separation - type.angle());
            if (orb <= policy.maxOrb(type, a.body(), b.body())) {
                return Optional.of(new Aspect(a.body(), b.body(), type, separation, orb));
            }
        }
        return Optional.empty();
    }
}
