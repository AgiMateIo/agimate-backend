package ru.agimate.agentworker.agent;

/**
 * The run's turn counter and the soft landing derived from it. An object rather than a {@code for}
 * variable because steering restarts the count mid-body, and the arithmetic is load-bearing: a reset
 * leaves the running turn as turn 1, so each absorption buys back exactly one turn.
 *
 * <p>The soft landing exists so iterative perfectionism (generate → check → «not quite» → again) ends
 * in a degraded but useful answer instead of {@code MaxTurnsExceeded} with all the work lost:
 * {@link AgiMateAgent#WRAP_UP_TURNS} turns before the cap the loop injects «finish with what you
 * have», and the last turn runs without tools, leaving the model nothing to do but write.
 */
final class TurnBudget {

    private final int maxTurns;
    /** Soft landing only for a meaningful cap — a tiny maxTurns (tests, debugging) is left alone. */
    private final boolean softLanding;
    private int turn;
    private int resets;

    TurnBudget(int maxTurns) {
        this.maxTurns = maxTurns;
        this.softLanding = maxTurns > AgiMateAgent.WRAP_UP_TURNS;
    }

    /** Advances to the next turn; {@code false} once the budget is spent. */
    boolean next() {
        return ++turn <= maxTurns;
    }

    int current() {
        return turn;
    }

    int max() {
        return maxTurns;
    }

    /** The turn the ephemeral wrap-up notice is injected on. */
    boolean wrapUpTurn() {
        return softLanding && turn == maxTurns - AgiMateAgent.WRAP_UP_TURNS + 1;
    }

    /** The last turn runs without tools, forcing the model to produce final text. */
    boolean toolless() {
        return softLanding && turn == maxTurns;
    }

    /** Past the cap the seam stops polling: queued messages simply run on their own afterwards. */
    boolean canSteer() {
        return resets < AgiMateAgent.MAX_STEERING_RESETS;
    }

    /**
     * An absorption restarts the budget — the new message deserves the full allowance — and re-arms
     * the soft landing with it. The running turn becomes turn 1 and finishes as such.
     */
    void reset() {
        resets++;
        turn = 1;
    }

    int resets() {
        return resets;
    }
}
