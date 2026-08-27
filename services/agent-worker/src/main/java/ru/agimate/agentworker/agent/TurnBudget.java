package ru.agimate.agentworker.agent;

/**
 * The run's turn counter and the soft landing derived from it. An object rather than a {@code for}
 * variable because steering restarts the count mid-body: a reassigned loop variable can only be read
 * by tracing what the update expression then does to it, and the arithmetic here is load-bearing —
 * a reset leaves the running turn as turn 1, so the next one is 2 and each absorption buys exactly
 * one turn back.
 *
 * <p>The policy constants stay on {@link AgiMateAgent}, next to the class javadoc that explains them
 * and the notice texts they govern.
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
