package ru.agimate.controlapi.connectors.internal.astro.calc;

/**
 * An aspect between two bodies.
 *
 * @param a           the first body
 * @param b           the second body
 * @param type        the aspect's type
 * @param actualAngle the actual angular distance, [0, 180]
 * @param orb         deviation from the aspect's exact angle
 */
public record Aspect(String a, String b, AspectType type, double actualAngle, double orb) {}
