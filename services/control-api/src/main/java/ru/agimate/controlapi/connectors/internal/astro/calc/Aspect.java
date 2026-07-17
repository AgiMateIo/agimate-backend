package ru.agimate.controlapi.connectors.internal.astro.calc;

/**
 * Аспект между двумя телами.
 *
 * @param a           первое тело
 * @param b           второе тело
 * @param type        тип аспекта
 * @param actualAngle фактическое угловое расстояние, [0, 180]
 * @param orb         отклонение от точного угла аспекта
 */
public record Aspect(String a, String b, AspectType type, double actualAngle, double orb) {}
