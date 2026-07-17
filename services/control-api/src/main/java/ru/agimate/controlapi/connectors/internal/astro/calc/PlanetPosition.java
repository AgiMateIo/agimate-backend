package ru.agimate.controlapi.connectors.internal.astro.calc;

/**
 * Геоцентрическая эклиптическая позиция тела (эклиптика даты, тропический зодиак).
 *
 * @param body       имя тела ("Sun".."Pluto")
 * @param longitude  эклиптическая долгота, [0, 360)
 * @param latitude   эклиптическая широта, градусы
 * @param retrograde ретроградно ли видимое движение (для Sun/Moon всегда false)
 */
public record PlanetPosition(String body, double longitude, double latitude, boolean retrograde) {

    public ZodiacSign sign() {
        return ZodiacSign.fromLongitude(longitude);
    }
}
