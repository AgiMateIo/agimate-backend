package ru.agimate.common.exception;

import lombok.Getter;

import java.time.Duration;

@Getter
public class ResourceExhaustedException extends ProjectBaseException {
    private final Duration cooldownPeriod;

    public ResourceExhaustedException(String message, Duration duration) {
        super(message);
        cooldownPeriod = duration;
    }
}
