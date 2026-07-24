package ru.agimate.controlapi.connectors.internal.sheets;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Тип колонки листа. Объявленный тип — то, что отличает лист от свободной сетки ячеек Excel: он
 * известен заранее, поэтому каст значения в SQL-агрегации безопасен, а не «повезёт/не повезёт».
 */
public enum SheetColumnType {

    NUMBER, TEXT, DATE, BOOL;

    /** Имя в wire-формате тулов (нижний регистр). */
    public String wire() {
        return name().toLowerCase();
    }

    public static SheetColumnType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ConnectorException("Column type is required. Allowed: " + allowed());
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid column type: '" + raw + "'. Allowed: " + allowed());
        }
    }

    public static String allowed() {
        return Arrays.stream(values()).map(SheetColumnType::wire).collect(Collectors.joining(", "));
    }
}
