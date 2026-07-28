package ru.agimate.controlapi.connectors.internal.sheets;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Type of a sheet's column. The declared type is what separates a sheet from Excel's free grid of
 * cells: it is known up front, so casting a value in SQL aggregation is safe rather than a matter of
 * luck.
 */
public enum SheetColumnType {

    NUMBER, TEXT, DATE, BOOL;

    /** The name in the tools' wire format (lower case). */
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
