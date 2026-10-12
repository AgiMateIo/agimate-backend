package ru.agimate.controlapi.service.mcp.oauth;

/** A valid request shows the consent page; an invalid one with a trusted redirect goes back to the client. */
public sealed interface CheckOutcome {
    record Consent(ConsentView view) implements CheckOutcome {}
    record Redirect(String redirectUrl) implements CheckOutcome {}
}
