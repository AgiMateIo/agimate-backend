package ru.agimate.userapi.service.auth;

import lombok.experimental.UtilityClass;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.nio.charset.StandardCharsets;

/**
 * What a password has to be, in one place: registration and reset must not diverge on it, or one of
 * the two ways into an account would be weaker than the other.
 *
 * <p>Length and nothing else. Rules about digits and capitals produce passwords predictable in one
 * way and forgotten in another; NIST stopped recommending them in 2017.
 */
@UtilityClass
public class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    /**
     * bcrypt reads 72 bytes and silently ignores everything after them. Unchecked, a long password
     * would be verified by its beginning alone, and no test would ever show it. Counted in bytes and
     * not characters on purpose: forty Cyrillic letters are already past this.
     */
    public static final int MAX_BYTES = 72;

    public static void validate(String password) {
        if (password.length() < MIN_LENGTH) {
            throw new BadRequestStatusException(
                    "The password must be at least " + MIN_LENGTH + " characters long");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BadRequestStatusException("The password is too long — up to " + MAX_BYTES + " bytes");
        }
    }
}
