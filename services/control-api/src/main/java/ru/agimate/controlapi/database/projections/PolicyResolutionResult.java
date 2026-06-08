package ru.agimate.controlapi.database.projections;

import java.util.UUID;

public interface PolicyResolutionResult {
    UUID getId();
    String getEffect();
    int getSpecificity();
}
