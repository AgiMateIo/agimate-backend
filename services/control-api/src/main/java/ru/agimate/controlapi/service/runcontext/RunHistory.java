package ru.agimate.controlapi.service.runcontext;

import java.util.List;

/**
 * A session's history for the next run, plus what the window says about disclosure
 * ({@code docs/decisions/progressive-disclosure.md}): the disclosed set is not stored anywhere, it
 * is read off the same turns the model is about to see.
 *
 * @param messages       the transcript, chronological
 * @param disclosedTools LLM-facing names of every tool the window called or had described, newest
 *                       first and distinct — a tool the model saw itself use must stay callable, or
 *                       it repeats the call by example and gets «unknown tool»
 * @param budgetLeft     bytes of the disclosure budget still unspent after the skill bodies kept in
 *                       the transcript; the context assembly spends the rest on schemas
 */
public record RunHistory(List<RunHistoryMessage> messages, List<String> disclosedTools, int budgetLeft) {

    public static RunHistory empty() {
        return new RunHistory(List.of(), List.of(), RunHistoryAssembler.DISCLOSED_BUDGET_BYTES);
    }
}
