package ru.agimate.common.security.jwt;

import io.jsonwebtoken.Claims;

public record WrappedJwt(
        String jwt,
        Claims claims
) {
}
