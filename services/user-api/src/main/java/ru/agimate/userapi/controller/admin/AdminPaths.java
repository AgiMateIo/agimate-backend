package ru.agimate.userapi.controller.admin;

import lombok.experimental.UtilityClass;

/**
 * The admin-only area of user-api, mirroring {@code /manage/admin} in control-api: the path is the gate —
 * the security chain demands {@code ROLE_ADMIN} for the whole prefix, so controllers here carry no
 * {@code @PreAuthorize} of their own, and mounting one outside {@link #PREFIX} drops the gate silently.
 *
 * <p>The prefix is relative to the service's context path ({@code /user/}), so callers see
 * {@code /user/admin/…}. It deliberately does not repeat that segment the way {@code UserController}
 * does — {@code /user/user/me} is a wart of its own, not a convention worth spreading.
 */
@UtilityClass
public class AdminPaths {

    public static final String PREFIX = "/admin";
}
