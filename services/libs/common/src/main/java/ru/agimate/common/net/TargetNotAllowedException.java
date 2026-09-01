package ru.agimate.common.net;

/**
 * An outbound address was refused by {@link PublicTargets}. Unchecked on purpose: the refusal
 * happens inside DNS-resolver hooks, whose signatures allow nothing else, and every call site
 * translates it into the exception of its own layer.
 *
 * <p>The message never names the resolved address. A caller who chose the URL learns only that the
 * target was refused — telling them <i>which</i> address a name answered with would turn the guard
 * itself into the internal-DNS oracle it exists to prevent.
 */
public class TargetNotAllowedException extends RuntimeException {

    public TargetNotAllowedException(String message) {
        super(message);
    }
}
