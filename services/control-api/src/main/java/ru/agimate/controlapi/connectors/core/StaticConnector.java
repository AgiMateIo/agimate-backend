package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.database.model.ConnectorTraits;

import java.util.Arrays;
import java.util.Optional;

/**
 * The catalogue rows with no handler. Their English texts live here as a handler's live in its class:
 * the source a translation falls back to, which the {@code connectors} row cannot be — it is written in
 * the installation's language.
 */
enum StaticConnector {

    APP("app", "App", ConnectorTraits.app(),
            "A connected app: the agent calls tools on a computer or phone that runs it "
                    + "— screenshots, files, local actions."),
    CLAUDE_CODE("claude-code", "Claude Code", ConnectorTraits.loopback(),
            "Claude Code as the executor: the agent takes the calls and runs them in your environment.");

    final String code;
    final String name;
    final ConnectorTraits traits;
    final String description;

    StaticConnector(String code, String name, ConnectorTraits traits, String description) {
        this.code = code;
        this.name = name;
        this.traits = traits;
        this.description = description;
    }

    static Optional<StaticConnector> of(String code) {
        return Arrays.stream(values()).filter(row -> row.code.equals(code)).findFirst();
    }
}
